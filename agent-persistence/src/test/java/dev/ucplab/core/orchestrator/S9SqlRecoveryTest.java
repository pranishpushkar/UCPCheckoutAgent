package dev.ucplab.core.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.approval.ApprovalRepository;
import dev.ucplab.core.intent.IntentRepository;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.intent.RecoveryScan;
import dev.ucplab.persistence.Database;
import dev.ucplab.persistence.Migrations;
import dev.ucplab.persistence.SqlApprovalRepository;
import dev.ucplab.persistence.SqlIntentRepository;
import dev.ucplab.persistence.SqlRecoveryScan;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * S9 on SQLite: the process dies, everything is closed, and a new process opens the same database file with a new
 * {@link Database}, new repositories, a new orchestrator and a new worker id. After the lease and a worker period it
 * recovers the intent: exactly one merchant order, and the invariants hold (I1, I4, I5, I7, checked from the merchant's
 * own records by the shared {@link Invariants}).
 *
 * <p>The crash points are the matrix rows around the complete call: 9 (write-ahead of complete), 11 (the merchant
 * call), 12 (resolving its answer) and 15 (the resend), and row 13 (the first reconciliation read) as the early
 * RECONCILING variant.
 */
class S9SqlRecoveryTest {

    @TempDir
    Path dir;

    private Path file;
    private Database db;
    private MutableClock clock;
    private final AtomicReference<IntentRepository> intents = new AtomicReference<>();
    private final AtomicReference<ApprovalRepository> approvals = new AtomicReference<>();
    private final AtomicReference<RecoveryScan> scan = new AtomicReference<>();

    /** A repository the harness holds for the whole run while the database behind it is closed and reopened. */
    private static <T> T indirect(Class<T> type, AtomicReference<T> target) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            try {
                return method.invoke(target.get(), args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }));
    }

    private CrashHarness.Stores stores(MutableClock harnessClock) {
        clock = harnessClock;
        file = dir.resolve("s9.db");
        Migrations.migrate(file);
        open();
        return new CrashHarness.Stores(indirect(IntentRepository.class, intents),
                indirect(ApprovalRepository.class, approvals), indirect(RecoveryScan.class, scan));
    }

    private void open() {
        db = Database.open(file, 4);
        intents.set(new SqlIntentRepository(db, clock));
        approvals.set(new SqlApprovalRepository(db));
        scan.set(new SqlRecoveryScan(db));
    }

    /** The process is gone: nothing may hold a connection, and a new Database is opened over the same file. */
    private void closeEverythingAndReopen() {
        assertThat(db.inUse()).as("a crash leaves no connection checked out").isZero();
        db.close();
        open();
    }

    @AfterEach
    void close() {
        db.close();
    }

    static Stream<Arguments> points() {
        return Stream.of(Boundary.values()).filter(b -> List.of(9, 11, 12, 13, 15).contains(b.row))
                .flatMap(b -> b.points.stream().filter(p -> !p.recovery()).map(p -> Arguments.of(b, p)));
    }

    // Covers: S9, I1, I4, I5, I7, NFR4 (crash at the complete boundary, restart over the same file: COMPLETED, one order)
    @ParameterizedTest(name = "row {0}: {1}")
    @MethodSource("points")
    void restartOverSameFile_completesWithOneOrder(Boundary row, Boundary.Point point) {
        assertThat(point.expected()).as("these rows all recover to COMPLETED").isEqualTo(IntentState.COMPLETED);
        CrashHarness h = new CrashHarness(this::stores);
        row.script.setup(h.merchant);
        CrashHarness.Process original = h.process("worker-1");
        h.arm(original, point);
        CrashMatrixContract.crashed(() -> row.script.run(h, original));
        assertThat(original.fired()).as("the armed crash fired: %s", point).isTrue();

        closeEverythingAndReopen();
        h.waitOutLeaseAndPeriod();
        h.recover(h.process("worker-new"), row.script);

        PurchaseIntent end = h.intent().orElseThrow();
        assertThat(end.state()).isEqualTo(IntentState.COMPLETED);
        assertThat(h.merchant.orders()).hasSize(1);
        CrashMatrixContract.assertInvariants(h, end);
    }

    // Covers: S9, I1, I5 (allowed S9 outcome: the process dies in RECONCILING, nothing reaches the merchant and the
    // reconciliation deadline has passed by the time a new process takes over, so the intent legitimately ends
    // ESCALATED with no order. It is not stuck (I5) and nothing was bought (I1); a human decides)
    @Test
    void restartAfterTheDeadline_endsEscalatedWithNoOrder() {
        CrashHarness h = new CrashHarness(this::stores);
        Script script = Script.ESCALATE;
        script.setup(h.merchant);
        CrashHarness.Process original = h.process("worker-1");
        h.arm(original, Boundary.Point.gateway(CrashingGateway.Op.GET, CrashPlan.Phase.BEFORE, 1, null, 0));
        CrashMatrixContract.crashed(() -> script.run(h, original));
        assertThat(original.fired()).isTrue();
        assertThat(h.intent().orElseThrow().state()).isEqualTo(IntentState.RECONCILING);
        assertThat(h.intent().orElseThrow().uncertainSince()).as("the deadline is persisted").isPresent();

        closeEverythingAndReopen();
        // The dead process is gone for longer than the whole reconciliation deadline (7.5, 60 s).
        h.clock.advance(h.config.reconcileDeadline().plus(Duration.ofSeconds(30)));
        h.waitOutLeaseAndPeriod();
        h.recover(h.process("worker-new"), script);

        PurchaseIntent end = h.intent().orElseThrow();
        assertThat(end.state()).isEqualTo(IntentState.ESCALATED);
        assertThat(h.merchant.orders()).isEmpty();
        CrashMatrixContract.assertInvariants(h, end);
    }
}
