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
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The handoff property tests of {@link HandoffPropertyTest} over SQLite (port parity, H5 C7b): same generator, seed
 * chain and invariants (I1, I3, I5, I7, I12, no complete), both merchant modes, the SQL adapters as the store. One database file serves the class and each
 * schedule starts by emptying the tables, as {@link SqlOrchestratorPropertyTest} does.
 *
 * <p>{@value #DEFAULT_TRIES} schedules per property and mode unless {@code -Dproperty.tries} says otherwise.
 */
class SqlHandoffPropertyTest {

    static final int DEFAULT_TRIES = 1_000;

    @TempDir
    static Path dir;

    private static Database db;

    @BeforeAll
    static void openDatabase() {
        Path file = dir.resolve("handoff-property.db");
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
        ScheduleExecutor executor = ScheduleExecutor.atHandoff(mode, SqlHandoffPropertyTest::freshStores);
        PropertyRunner.check(invariant + " [handoff, SQL " + mode + "]",
                PropertySettings.fromSystemProperties(DEFAULT_TRIES), schedule -> property.apply(executor.run(schedule)));
        assertThat(db.inUse()).as("every connection came back").isZero();
    }

    // Covers: I1, 7.2 Handoff merchants, 14.1 Merchant strictness (on SQLite)
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void property_atMostOneOrderPerIntent_atAHandoffMerchant(FaultyMerchantModel.Mode mode) {
        check("I1", mode, Invariants::i1);
    }

    // Covers: I3, 7.2 Handoff merchants, 14.1 Merchant strictness (on SQLite)
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void property_completeOnlyUnderValidApproval_atAHandoffMerchant(FaultyMerchantModel.Mode mode) {
        check("I3", mode, Invariants::i3);
    }

    // Covers: I7, 7.2 Handoff merchants, 14.1 Merchant strictness (on SQLite)
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void property_resendsReuseKeyAndBody_atAHandoffMerchant(FaultyMerchantModel.Mode mode) {
        check("I7", mode, Invariants::i7);
    }

    // Covers: I5, 7.6 (APPROVED at a handoff merchant ends within 5 minutes of the approval, plus one pass and one GET),
    // 14.1 Merchant strictness (on SQLite)
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void property_everyIntentTerminalOrEscalatedWithinItsBound_atAHandoffMerchant(FaultyMerchantModel.Mode mode) {
        check("I5", mode, Invariants::i5);
    }

    // Covers: I12, 7.2 hand_off_refused, 19, 14.1 Merchant strictness (on SQLite)
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void property_noMutatingCallReachesAMerchantFailingI12_atAHandoffMerchant(FaultyMerchantModel.Mode mode) {
        check("I12", mode, Invariants::i12);
    }

    // Covers: 7.2 Handoff merchants (no complete is sent or written for a handoff intent), 14.1 (on SQLite)
    @ParameterizedTest(name = "{0} merchant")
    @EnumSource(FaultyMerchantModel.Mode.class)
    void property_noCompleteIsSentOrWritten_atAHandoffMerchant(FaultyMerchantModel.Mode mode) {
        check("handoff", mode, Invariants::handoffNeverCompletes);
    }
}
