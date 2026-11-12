package dev.ucplab.core.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.persistence.Database;
import dev.ucplab.persistence.Failpoint;
import dev.ucplab.persistence.Migrations;
import dev.ucplab.persistence.SqlApprovalRepository;
import dev.ucplab.persistence.SqlIntentRepository;
import dev.ucplab.persistence.SqlRecoveryScan;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;

/**
 * The crash matrix of m3-plan.md heading 8 over SQLite: the same rows, the same expectations and the same invariant
 * checks as the in-memory run, through the {@link CrashHarness.Stores} factory. One database file serves the class; each
 * run empties the tables instead of migrating again. The table written to {@code target/crash-matrix-sql.md} must equal
 * the in-memory one (the contract compares them), and adds row 20: a failure inside the two-statement writes of
 * {@link SqlIntentRepository}.
 */
class SqlCrashMatrixTest extends CrashMatrixContract {

    static {
        System.setProperty("crash.matrix.adapter", "sql");
    }

    @TempDir
    static Path dir;

    private static Database db;

    /** Armed by a row-20 test: fails the nth time the named failpoint is reached. */
    private final Map<String, AtomicInteger> failpointHits = new LinkedHashMap<>();
    private volatile String armedPoint;
    private volatile int armedNth;
    private volatile boolean failpointFired;

    @BeforeAll
    static void openDatabase() {
        Path file = dir.resolve("matrix.db");
        Migrations.migrate(file);
        db = Database.open(file, 4);
    }

    @AfterAll
    static void closeDatabase() {
        db.close();
    }

    @Override
    CrashHarness.Stores newStores(MutableClock clock) {
        db.write(c -> {
            try (Statement s = c.createStatement()) {
                // Children first: the foreign keys point at purchase_intent.
                for (String table : new String[] {"approval", "merchant_attempt", "intent_event", "purchase_intent"}) {
                    s.executeUpdate("DELETE FROM " + table);
                }
            }
            return null;
        });
        failpointHits.clear();
        failpointFired = false;
        Failpoint failpoint = point -> {
            int n = failpointHits.computeIfAbsent(point, k -> new AtomicInteger()).incrementAndGet();
            if (point.equals(armedPoint) && n == armedNth && !failpointFired) {
                failpointFired = true;
                // What a kill does: the process stops inside the transaction. An Error is not caught by Database.write,
                // so the connection is discarded (closed, which rolls the open transaction back).
                throw new InterceptingGateway.SimulatedCrash("failpoint " + point + " #" + n);
            }
        };
        SqlIntentRepository intents = new SqlIntentRepository(db, clock, failpoint);
        SqlApprovalRepository approvals = new SqlApprovalRepository(db);
        return new CrashHarness.Stores(intents, approvals, new SqlRecoveryScan(db));
    }

    /**
     * Row 20 points. A failure between the two statements of a write-ahead, or after both and before the commit, undoes
     * the whole transaction, so the world is as if the process had died before that call: the expectation of each is the
     * in-memory BEFORE point of the same repository call. Derived from the matrix itself so the two cannot drift.
     */
    static Stream<Arguments> row20() {
        Map<String, Arguments> unique = new LinkedHashMap<>();
        for (Boundary b : Boundary.values()) {
            for (Boundary.Point p : b.points) {
                boolean writeAhead = p.kind() == Boundary.Kind.INTENT && !p.recovery()
                        && p.phase() == CrashPlan.Phase.BEFORE
                        && (p.op().equals("RECORD_ATTEMPT") || p.op().equals("RESOLVE_ATTEMPT"));
                if (!writeAhead) {
                    continue;
                }
                String recordOrResolve = p.op().equals("RECORD_ATTEMPT") ? "recordAttempt" : "resolveAttempt";
                for (String where : new String[] {"between", "after"}) {
                    String failpoint = recordOrResolve + ":" + where;
                    unique.putIfAbsent(b.script + "/" + failpoint + "/" + p.nth(),
                            Arguments.of(b.script, failpoint, p.nth(), p.expected(), p.orders()));
                }
            }
        }
        return unique.values().stream();
    }

    // Covers: 7.3 steps 3 and 5, NFR4, S9 (row 20: the process dies inside a write-ahead transaction; the transaction is
    // rolled back, a new worker recovers, and the end state is the in-memory BEFORE result with at most one order)
    @ParameterizedTest(name = "row 20: {0} {1} #{2}")
    @MethodSource("row20")
    void recovery_endsAsBeforeTheCall_whenTheWriteAheadFailsInside(
            Script script, String failpoint, int nth, IntentState expected, int orders) {
        CrashHarness h = new CrashHarness(this::newStores);
        script.setup(h.merchant);
        armedPoint = failpoint;
        armedNth = nth;
        CrashHarness.Process original = h.process("worker-1");
        crashed(() -> script.run(h, original));
        assertThat(failpointFired).as("the failpoint fired: %s #%d", failpoint, nth).isTrue();
        h.waitOutLeaseAndPeriod();
        h.recover(h.process("worker-2"), script);

        Optional<PurchaseIntent> intent = h.intent();
        IntentState end = intent.map(PurchaseIntent::state).orElse(null);
        int count = h.merchant.orders().size();
        recordResult(20, script + " " + failpoint + " #" + nth, end == null ? "no intent" : end.name(), count);
        assertThat(end).as("end state after %s #%d", failpoint, nth).isEqualTo(expected);
        assertThat(count).as("orders").isEqualTo(orders).isLessThanOrEqualTo(1);
        intent.ifPresent(i -> assertInvariants(h, i));
        assertThat(db.inUse()).as("every connection came back").isZero();
    }
}
