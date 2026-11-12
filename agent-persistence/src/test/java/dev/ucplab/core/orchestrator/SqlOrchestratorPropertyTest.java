package dev.ucplab.core.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.PropertySettings;
import dev.ucplab.core.merchant.fake.FaultyMerchantModel;
import dev.ucplab.persistence.Database;
import dev.ucplab.persistence.Migrations;
import dev.ucplab.persistence.SqlApprovalRepository;
import dev.ucplab.persistence.SqlIntentRepository;
import dev.ucplab.persistence.SqlRecoveryScan;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The seeded property tests of {@link OrchestratorPropertyTest} over SQLite (section 14, level 2; M3). Same generator,
 * same schedules, same seed chain, same invariants, in both merchant modes; the repositories are the SQL adapters. One
 * database file serves the class and each schedule starts by emptying the tables (a Flyway run per schedule would be far
 * too slow). Replay a failure as before: {@code -Dproperty.seed=<seed> -Dproperty.tries=1 -pl agent-persistence}.
 *
 * <p>{@value #DEFAULT_TRIES} schedules per property and mode unless {@code -Dproperty.tries} says otherwise.
 */
class SqlOrchestratorPropertyTest {

    static final int DEFAULT_TRIES = 1_000;

    @TempDir
    static Path dir;

    private static Database db;

    @BeforeAll
    static void openDatabase() {
        Path file = dir.resolve("property.db");
        Migrations.migrate(file);
        db = Database.open(file, 2);
    }

    @AfterAll
    static void closeDatabase() {
        db.close();
    }

    /** An empty store over {@code clock}: the tables are cleared, children first (the foreign keys). */
    static CrashHarness.Stores freshStores(MutableClock clock) {
        db.write(c -> {
            try (Statement s = c.createStatement()) {
                for (String table : new String[] {"approval", "merchant_attempt", "intent_event", "purchase_intent"}) {
                    s.executeUpdate("DELETE FROM " + table);
                }
            }
            return null;
        });
        return new CrashHarness.Stores(new SqlIntentRepository(db, clock), new SqlApprovalRepository(db),
                new SqlRecoveryScan(db));
    }

    private static void check(String invariant, FaultyMerchantModel.Mode mode,
            Function<Observations, List<String>> property) {
        ScheduleExecutor executor = new ScheduleExecutor(ScheduleExecutor.DEFAULT_ORCHESTRATOR, mode,
                SqlOrchestratorPropertyTest::freshStores);
        PropertyRunner.check(invariant + " [SQL " + mode + "]",
                PropertySettings.fromSystemProperties(DEFAULT_TRIES), schedule -> property.apply(executor.run(schedule)));
        assertThat(db.inUse()).as("every connection came back").isZero();
    }

    // Covers: I1, S2, S3, S4, S5, S6, S7, S9, S13, S16, S17, P7, P8, 14.1 Merchant strictness (on SQLite)
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void property_atMostOneOrderPerIntent_forEveryGeneratedSchedule(FaultyMerchantModel.Mode mode) {
        check("I1", mode, Invariants::i1);
    }

    // Covers: I3, I2, S5, S16, P7, P8, 9.2, 14.1 Merchant strictness (on SQLite)
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void property_completeOnlyUnderValidUnconsumedMatchingApproval_forEveryGeneratedSchedule(
            FaultyMerchantModel.Mode mode) {
        check("I3", mode, Invariants::i3);
    }

    // Covers: I4, S2, S9, S17, 14.1 Merchant strictness (on SQLite)
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void property_sessionNeverChangesAfterFirstComplete_forEveryGeneratedSchedule(FaultyMerchantModel.Mode mode) {
        check("I4", mode, Invariants::i4);
    }

    // Covers: I5, NFR4, S9, 7.5, 7.6 (liveness bounds), 14.1 Merchant strictness (on SQLite)
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void property_everyIntentTerminalOrEscalatedWithinItsBound_forEveryGeneratedSchedule(
            FaultyMerchantModel.Mode mode) {
        check("I5", mode, Invariants::i5);
    }

    // Covers: I7, S2, S3, S9, S16, S17, 7.4, 14.1 Merchant strictness (on SQLite)
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void property_resendsReuseKeyAndBodyAndNewApprovalGetsNewKey_forEveryGeneratedSchedule(
            FaultyMerchantModel.Mode mode) {
        check("I7", mode, Invariants::i7);
    }

    // Covers: 14.1, NFR4 (differential: the SQL adapter and the in-memory one behave alike on the same schedules: the
    // same orders, the same end states, the same merchant traffic, so a property that passes on one says something about
    // the other)
    @Test
    void sql_behavesLikeInMemory_onTheSameSchedules() {
        ScheduleExecutor memory = new ScheduleExecutor(ScheduleExecutor.DEFAULT_ORCHESTRATOR,
                FaultyMerchantModel.Mode.LAB);
        ScheduleExecutor sql = new ScheduleExecutor(ScheduleExecutor.DEFAULT_ORCHESTRATOR, FaultyMerchantModel.Mode.LAB,
                SqlOrchestratorPropertyTest::freshStores);
        long seed = PropertySettings.DEFAULT_SEED;
        List<String> differences = new ArrayList<>();
        for (int i = 0; i < 300; i++, seed = PropertySettings.next(seed)) {
            Schedule schedule = new ScheduleGenerator(seed).generate();
            Observations a = memory.run(schedule);
            Observations b = sql.run(schedule);
            String ends = "";
            if (!ends(a).equals(ends(b)) || a.orders().size() != b.orders().size()
                    || a.calls().size() != b.calls().size() || a.crashes().size() != b.crashes().size()) {
                differences.add("seed " + seed + ": end states " + ends(a) + " vs " + ends(b) + ", orders "
                        + a.orders().size() + " vs " + b.orders().size() + ", calls " + a.calls().size() + " vs "
                        + b.calls().size());
            }
        }
        assertThat(differences).isEmpty();
    }

    private static List<String> ends(Observations o) {
        return o.samples().stream().filter(s -> s.phase() == Observations.Phase.END)
                .map(s -> s.intent().value() + "=" + s.state()).toList();
    }
}
