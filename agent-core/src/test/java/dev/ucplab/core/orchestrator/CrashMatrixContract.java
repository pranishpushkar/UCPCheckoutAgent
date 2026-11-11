package dev.ucplab.core.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.approval.ApprovalRecord;
import dev.ucplab.core.approval.ApprovalStatus;
import dev.ucplab.core.approval.ApprovalRepository;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.IntentRepository;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.fake.CallRecord;
import dev.ucplab.core.merchant.fake.MerchantCall;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The crash matrix of m3-plan.md heading 8 (7.3, 7.6, NFR4, S9). Every {@link Boundary} point is one test: run the row's
 * script until the armed crash kills the process, start a new worker id over the same store once the lease and a worker
 * period have passed, recover, then check the end state, the order count, and the invariants against the merchant's
 * own records. An adapter runs the same matrix by supplying its {@link CrashHarness.Stores}; time is the harness's
 * {@link MutableClock}, so nothing sleeps.
 */
abstract class CrashMatrixContract {

    /** The adapter under test, over {@code clock}. */
    abstract CrashHarness.Stores newStores(MutableClock clock);

    private record Result(int row, String crashPoint, String endState, int orders) {}

    private static final ConcurrentLinkedQueue<Result> RESULTS = new ConcurrentLinkedQueue<>();

    static Stream<Arguments> points() {
        return Stream.of(Boundary.values()).flatMap(b -> b.points.stream().map(p -> Arguments.of(b, p)));
    }

    // Covers: I1, I3, I4, I5, I7, NFR4, S9 (the process dies at the point; a new worker recovers; one order at most)
    @ParameterizedTest(name = "row {0}: {1}")
    @MethodSource("points")
    void recovery_endsInExpectedState_afterCrashAt(Boundary row, Boundary.Point point) {
        CrashHarness h = new CrashHarness(this::newStores);
        row.script.setup(h.merchant);
        CrashHarness.Process original = h.process("worker-1");
        if (point.recovery()) {
            // The original process dies at its create call, so there is an intent for the recovery pass to find.
            h.arm(original, Boundary.Point.gateway(CrashingGateway.Op.CREATE, CrashPlan.Phase.BEFORE, 1, null, 0));
        } else {
            h.arm(original, point);
        }
        crashed(() -> row.script.run(h, original));
        assertThat(original.fired()).as("the armed crash fired: %s", point).isTrue();
        h.waitOutLeaseAndPeriod();

        CrashHarness.Process restarted = h.process("worker-2");
        if (point.recovery()) {
            CrashHarness.Process dying = restarted;
            h.arm(dying, point);
            crashed(() -> h.recoverOnce(dying));
            assertThat(dying.fired()).as("the armed crash fired during recovery: %s", point).isTrue();
            h.waitOutLeaseAndPeriod();
            restarted = h.process("worker-3");
        }
        h.recover(restarted, row.script);

        Optional<PurchaseIntent> intent = h.intent();
        IntentState end = intent.map(PurchaseIntent::state).orElse(null);
        int orders = h.merchant.orders().size();
        RESULTS.add(new Result(row.row, point.toString(), end == null ? "no intent" : end.name(), orders));
        assertThat(end).as("end state after %s", point).isEqualTo(point.expected());
        assertThat(orders).as("orders after %s", point).isEqualTo(point.orders()).isLessThanOrEqualTo(1);
        intent.ifPresent(i -> assertInvariants(h, i));
    }

    private static void crashed(Runnable action) {
        try {
            action.run();
        } catch (InterceptingGateway.SimulatedCrash expected) {
            // the process died here
        }
    }

    /** I1, I3, I4, I5, I7 and the cancel rule of 7.6, read from the merchant's records and the stores. */
    private void assertInvariants(CrashHarness h, PurchaseIntent intent) {
        List<MerchantAttempt> attempts = h.stores.intents().attempts(intent.id());
        // I5: the intent is terminal, or waits only for the owner.
        assertThat(intent.state().isTerminal() || intent.state() == IntentState.AWAITING_APPROVAL)
                .as("I5 settled: %s", intent).isTrue();
        // 7.6: an owed cancel is done or given up, in at most three attempts; a create is probed at most three times.
        assertThat(intent.cancelPending()).as("no cancel still owed").isFalse();
        assertThat(attempts.stream().filter(a -> a.op() == AttemptOp.CANCEL)).hasSizeLessThanOrEqualTo(3);
        assertThat(attempts.stream().filter(a -> a.op() == AttemptOp.CREATE)).hasSizeLessThanOrEqualTo(4);
        // I7: every complete under one key carries a byte-identical body.
        Map<Integer, List<MerchantAttempt>> byRevision = attempts.stream().filter(a -> a.op() == AttemptOp.COMPLETE)
                .collect(Collectors.groupingBy(MerchantAttempt::revision, TreeMap::new, Collectors.toList()));
        byRevision.forEach((revision, sent) -> {
            assertThat(sent).extracting(a -> a.idempotencyKey().orElseThrow()).as("I7 key, revision %d", revision)
                    .containsOnly(sent.get(0).idempotencyKey().orElseThrow());
            assertThat(sent).extracting(a -> a.requestBody().orElseThrow()).as("I7 body, revision %d", revision)
                    .containsOnly(sent.get(0).requestBody().orElseThrow());
        });
        // I4: every complete the merchant received targets the intent's one session.
        List<CallRecord> delivered = h.merchant.log().stream()
                .filter(c -> c.call() == MerchantCall.COMPLETE && c.delivered()).toList();
        // I3, I10: a complete reached the merchant only after the owner approved. A crash between the write-ahead and the
        // move to CONSUMED leaves the approval APPROVED, which is benign (plan row 10: single use is the intent's state).
        if (!delivered.isEmpty()) {
            assertThat(delivered).extracting(c -> c.sessionId().orElseThrow()).as("I4 session")
                    .containsOnly(intent.sessionId().orElseThrow());
            List<ApprovalRecord> approvals = h.stores.approvals().listForSystem(intent.id());
            assertThat(approvals).as("I3 approval decided by the owner").anyMatch(a ->
                    (a.status() == ApprovalStatus.CONSUMED || a.status() == ApprovalStatus.APPROVED)
                            && a.decidedBy().equals(Optional.of(intent.owner())));
        }
        // I1: a failed or rejected intent leaves no open session behind.
        if (intent.state() == IntentState.FAILED_NOT_CHARGED || intent.state() == IntentState.REJECTED) {
            intent.sessionId().ifPresent(s -> assertThat(h.merchant.session(s).orElseThrow().status())
                    .as("session of a failed intent is cancelled").isEqualTo(CheckoutStatus.CANCELED));
        }
        if (intent.state() == IntentState.COMPLETED) {
            assertThat(intent.orderId()).isPresent();
        }
    }

    // Covers: NFR4 (the matrix has every row of the plan, in order)
    @Test
    void boundaries_haveEveryRowOfThePlan() {
        assertThat(Stream.of(Boundary.values()).map(b -> b.row).toList())
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 20).boxed().toList());
        assertThat(Stream.of(Boundary.values()).filter(b -> b.points.isEmpty()).map(b -> b.row))
                .as("only the SQL-statement row has no in-memory point").containsExactly(20);
    }

    /** Ports and methods no row can crash, because the orchestrator never calls them (checked below). */
    private static final Set<String> NOT_CALLED_BY_CORE = Set.of(
            "GATEWAY.DISCOVER", "INTENT.RESOLVE_ATTEMPT_UNFENCED");

    // Covers: NFR4 (every method of the intent and approval repositories and of the gateway appears in a row)
    @Test
    void boundaries_crashEveryPortMethod_exceptThoseCoreNeverCalls() {
        Set<String> covered = Stream.of(Boundary.values()).flatMap(b -> b.points.stream())
                .map(p -> p.kind() + "." + p.op()).collect(Collectors.toSet());
        Set<String> expected = new java.util.TreeSet<>();
        port(IntentRepository.class, CrashingIntentRepository.Op.class, "INTENT", expected);
        port(ApprovalRepository.class, CrashingApprovalRepository.Op.class, "APPROVAL", expected);
        port(MerchantGateway.class, CrashingGateway.Op.class, "GATEWAY", expected);
        expected.removeAll(NOT_CALLED_BY_CORE);

        assertThat(covered).containsAll(expected);
        assertThat(covered).doesNotContainAnyElementsOf(NOT_CALLED_BY_CORE);
    }

    /** Checks the decorator's Op enum names every method of the port, and adds each to {@code out}. */
    private static <O extends Enum<O>> void port(Class<?> port, Class<O> ops, String kind, Set<String> out) {
        Set<String> names = EnumSet.allOf(ops).stream().map(Enum::name).collect(Collectors.toSet());
        Set<String> seen = new java.util.TreeSet<>();
        for (Method m : port.getDeclaredMethods()) {
            String op = m.getName().replaceAll("([A-Z])", "_$1").toUpperCase(Locale.ROOT);
            if (m.getName().equals("resolveAttempt")) {
                op = m.getParameterCount() == 4 ? "RESOLVE_ATTEMPT_UNFENCED" : "RESOLVE_ATTEMPT";
            }
            assertThat(names).as("%s has an Op for %s", kind, m).contains(op);
            seen.add(op);
        }
        assertThat(seen).as("%s Op constants all name a method of the port", kind).containsExactlyInAnyOrderElementsOf(names);
        seen.forEach(op -> out.add(kind + "." + op));
    }

    // Covers: NFR4 (the methods no row crashes are never called by the orchestrator, in any script, crash-free)
    @Test
    void orchestrator_neverCallsPortMethodsWithoutARow_inAnyScript() {
        for (Script script : Script.values()) {
            CrashHarness h = new CrashHarness(this::newStores);
            script.setup(h.merchant);
            CrashHarness.Process p = h.process("worker-1");
            script.run(h, p);
            h.recover(p, script);
            assertThat(p.gatewayPlan().calls(CrashingGateway.Op.DISCOVER)).as(script.name()).isZero();
            assertThat(p.intentPlan().calls(CrashingIntentRepository.Op.RESOLVE_ATTEMPT_UNFENCED)).as(script.name()).isZero();
        }
    }

    @AfterAll
    static void writeResultTable() throws IOException {
        List<Result> results = new ArrayList<>(RESULTS);
        results.sort(java.util.Comparator.comparingInt(Result::row).thenComparing(Result::crashPoint));
        String table = results.stream()
                .map(r -> "| " + r.row() + " | " + r.crashPoint() + " | " + r.endState() + " | " + r.orders() + " |")
                .collect(Collectors.joining("\n", "| row | crash point | end state | orders |\n|---|---|---|---|\n", "\n"));
        Path out = Path.of("target", "crash-matrix-" + System.getProperty("crash.matrix.adapter", "in-memory") + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, table);
    }
}
