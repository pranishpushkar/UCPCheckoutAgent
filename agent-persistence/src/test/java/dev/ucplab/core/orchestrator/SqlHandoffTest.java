package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.orchestrator.Lab.SHOP;
import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.merchant.fake.FaultyMerchantModel;
import dev.ucplab.core.merchant.fake.MerchantCall;
import dev.ucplab.persistence.Database;
import dev.ucplab.persistence.Failpoint;
import dev.ucplab.persistence.Migrations;
import dev.ucplab.persistence.SqlApprovalRepository;
import dev.ucplab.persistence.SqlIntentRepository;
import dev.ucplab.persistence.SqlRecoveryScan;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Port parity for the handoff path (H5 C7b): every case of {@link HandoffTest}, the deadline cases included, over the
 * SQL adapters, each lab on its own SQLite file. Plus the SQL side of the boundary between the re-quote's get row and the
 * move to QUOTING: one transaction, so a failure inside it leaves neither.
 */
class SqlHandoffTest extends HandoffTest {

    @TempDir
    static Path dir;

    private static final AtomicInteger FILES = new AtomicInteger();
    private static final List<Database> OPEN = new ArrayList<>();
    /** The failpoint the next lab's intent repository hits: none, or the name of the point that throws. */
    private static final AtomicReference<String> ARMED = new AtomicReference<>("");

    private static final class SimulatedFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SimulatedFailure(String point) {
            super("failpoint " + point);
        }
    }

    @AfterAll
    static void closeDatabases() {
        OPEN.forEach(Database::close);
    }

    /** A fresh, migrated SQLite store over {@code clock}, whose intent repository throws at the armed failpoint. */
    static CrashHarness.Stores sqlStores(MutableClock clock) {
        Path file = dir.resolve("handoff-" + FILES.incrementAndGet() + ".db");
        Migrations.migrate(file);
        Database db = Database.open(file, 2);
        synchronized (OPEN) {
            OPEN.add(db);
        }
        Failpoint failpoint = point -> {
            if (ARMED.compareAndSet(point, "")) {
                throw new SimulatedFailure(point);
            }
        };
        return new CrashHarness.Stores(new SqlIntentRepository(db, clock, failpoint), new SqlApprovalRepository(db),
                new SqlRecoveryScan(db));
    }

    @Override
    protected Lab newLab() {
        Lab lab = new Lab(OrchestratorConfig.defaults(), FaultyMerchantModel.Mode.LAB, SqlHandoffTest::sqlStores);
        lab.eventLog = ((SqlIntentRepository) lab.intents)::events;
        return lab;
    }

    // Covers: 7.2 hand_off_changed, 7.3 step 3, S9 (on SQLite the re-quote's get row and the move to QUOTING are one
    // transaction: a failure between or after the two writes leaves the intent APPROVED with no get row; the next pass
    // reads again and re-quotes, and the cycle then starts at its get row)
    @Test
    void aFailureInsideTheRequoteWrite_leavesNeitherTheGetRowNorQuoting() {
        for (String point : List.of(Failpoint.RECORD_ATTEMPT_BETWEEN, Failpoint.RECORD_ATTEMPT_AFTER)) {
            Lab one = newLab();
            one.entries = lab.entries;
            Lab.Worker worker = one.worker("w", new RewritingGateway(one.merchant).rewrite(MerchantCall.GET,
                    view(Optional.of(URL), v -> total(v, v.totalMinor() + 1))));
            IntentId id = worker.orchestrator().propose(one.alice(), SHOP, TWO_SHIRTS, "buy").intentId();
            assertThat(one.drive(worker, id)).isEqualTo(IntentState.AWAITING_APPROVAL);
            one.decide(worker, id, true);
            ARMED.set(point);

            assertThatThrownBy(() -> worker.orchestrator().advance(id)).as(point)
                    .isInstanceOf(SimulatedFailure.class);

            assertThat(one.intent(id).state()).as(point).isEqualTo(IntentState.APPROVED);
            assertThat(one.intent(id).requoteCount()).isZero();
            assertThat(one.intents.attempts(id)).as(point).noneMatch(a -> a.op() == AttemptOp.GET);

            one.clock.advance(one.config.lease().plusSeconds(1));
            Lab.Worker next = one.worker("w2", new RewritingGateway(one.merchant).rewrite(MerchantCall.GET,
                    view(Optional.of(URL), v -> total(v, v.totalMinor() + 1))));
            next.orchestrator().advance(id);
            assertThat(one.intent(id).state()).as(point).isEqualTo(IntentState.QUOTING);
            assertThat(one.drive(next, id)).isEqualTo(IntentState.AWAITING_APPROVAL);
            assertThat(one.intents.attempts(id)).extracting(a -> a.op())
                    .containsExactly(AttemptOp.CREATE, AttemptOp.GET, AttemptOp.UPDATE);
        }
    }
}
