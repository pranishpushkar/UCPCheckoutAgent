package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CatalogPage;
import dev.ucplab.core.merchant.CatalogProduct;
import dev.ucplab.core.merchant.CatalogSearch;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.faultproxy.RuleSet;
import dev.ucplab.ucp.CircuitBreaker;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A lane of three merchants and an agent stack over them (m6a-plan C11, 13.1): each merchant has its own catalog,
 * breaker, limiter and order counter, and the real registry sits in front of them.
 */
class MultiMerchantLaneTest {

    private static final TimeScale SCALE = TimeScale.of(4);
    private static final MerchantId A = Scenarios.SHOP;
    private static final MerchantId B = Scenarios.SHOP_B;
    private static final MerchantId C = Scenarios.SHOP_C;

    @TempDir
    Path dir;

    private static byte[] key() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private static AuthContext auth(AgentStack stack, String user) {
        return new AuthContext(new UserId(user), user + "@eval.example", stack.clock.instant(), "s");
    }

    private static List<String> ids(CallOutcome<CatalogPage> outcome) {
        assertThat(outcome).isInstanceOf(CallOutcome.Ok.class);
        return ((CallOutcome.Ok<CatalogPage>) outcome).value().products().stream().map(CatalogProduct::id).toList();
    }

    /** Proposes {@code item} at {@code merchant}, approves it as the user would, and drives it to a terminal state. */
    private static IntentId buy(AgentStack stack, MerchantId merchant, String item, String user) throws Exception {
        AuthContext auth = auth(stack, user);
        IntentId id = stack.orchestrator.propose(auth, merchant, List.of(new ProposedLine(item, 1)), "lane test")
                .intentId();
        AutoApprover approver = new AutoApprover(stack.approvalService, AutoApprover.Policy.APPROVE_ALL,
                () -> auth(stack, user), stack.clock);
        Instant limit = Instant.now().plus(Duration.ofSeconds(20));
        while (!stack.intents.findForSystem(id).orElseThrow().state().isTerminal()) {
            if (approver.tick(id)) {
                stack.worker.runOnce();
            }
            assertThat(Instant.now()).as("the purchase of " + item + " ends").isBefore(limit);
            Thread.sleep(20);
        }
        return id;
    }

    // Covers: 13.1, m6a-plan C11 (a lane of three has three merchants and three proxies, each of its own, with their own
    // ids; a lane started the old way has one, whose accessors are unchanged)
    @Test
    void laneOfThree_hasThreeMerchantsAndThreeProxies_andALaneOfOneHasOne() throws Exception {
        try (Lane three = Lane.start(0, key(), dir, 3); Lane one = Lane.start(1, key(), dir)) {
            assertThat(three.merchantCount()).isEqualTo(3);
            assertThat(three.merchantIds()).containsExactly(A, B, C);
            assertThat(List.of(three.merchant(0).baseUrl(), three.merchant(1).baseUrl(), three.merchant(2).baseUrl()))
                    .doesNotHaveDuplicates();
            assertThat(List.of(three.agentFacingUrl(0), three.agentFacingUrl(1), three.agentFacingUrl(2)))
                    .doesNotHaveDuplicates();
            assertThat(three.merchant()).isSameAs(three.merchant(0));
            assertThat(three.proxy()).isSameAs(three.proxy(0));
            assertThat(one.merchantCount()).isEqualTo(1);
            assertThat(one.merchantIds()).containsExactly(A);
        }
    }

    // Covers: 13.1, 19, m6a-plan C11 (the stack has one gateway and one catalog client per merchant, and the real registry
    // started over them: every merchant is ACTIVE, and each catalog is its merchant's own)
    @Test
    void stackOverThreeMerchants_hasTheRealRegistry_andACatalogForEach() throws Exception {
        try (Lane lane = Lane.start(0, key(), dir, 3);
             AgentStack stack = AgentStack.forLane(lane, lane.newDatabase("catalogs"), SCALE, 1,
                     new SequentialIdGenerator("cat"), Scenarios.PAY_OK)) {
            assertThat(stack.registry().active()).extracting(e -> e.id()).containsExactly(A, B, C);
            var catalogs = stack.toolPorts().catalogs();
            assertThat(catalogs.keySet()).containsExactlyInAnyOrder(A, B, C);

            assertThat(ids(catalogs.get(A).search(new CatalogSearch("mug", 5)))).containsExactly("item_456");
            assertThat(ids(catalogs.get(B).search(new CatalogSearch("mug", 5)))).containsExactly("b_mug_01");
            assertThat(ids(catalogs.get(C).search(new CatalogSearch("mug", 5)))).containsExactly("c_mug_01");
            assertThat(stack.registry().find(B).orElseThrow().status()).isEqualTo(MerchantStatus.ACTIVE);
        }
    }

    // Covers: 8.3, 19, S20, m6a-plan C11 (failures at B's proxy open B's breaker and spend B's limiter, and A's and C's
    // are as they were: one breaker and one limiter per merchant, none shared)
    @Test
    void breakersAndLimiters_areOnePerMerchant() throws Exception {
        try (Lane lane = Lane.start(0, key(), dir, 3);
             AgentStack stack = AgentStack.forLane(lane, lane.newDatabase("breakers"), SCALE, 1,
                     new SequentialIdGenerator("brk"), Scenarios.PAY_OK)) {
            lane.proxy(1).install(RuleSet.parse("{\"seed\":1,\"rules\":[{\"id\":\"catalog-down\",\"match\":"
                    + "{\"pathPattern\":\"/ucp/v1/catalog/*\"},\"action\":{\"type\":\"fail_before_forward\","
                    + "\"status\":503}}]}"));
            var catalogs = stack.toolPorts().catalogs();
            catalogs.get(B).search(new CatalogSearch("mug", 3));
            catalogs.get(B).search(new CatalogSearch("mug", 3));

            assertThat(stack.breakers().forMerchant(B).state()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(stack.breakers().forMerchant(A).state()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(stack.breakers().forMerchant(C).state()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(stack.breakers().forMerchant(A)).isNotSameAs(stack.breakers().forMerchant(B));
            assertThat(stack.breakers().limiterFor(A)).isNotSameAs(stack.breakers().limiterFor(B));
            // B spent its tokens on the failed searches' attempts (burst 10); A spent one, on the registry's discovery.
            assertThat(stack.breakers().limiterFor(A).available() - stack.breakers().limiterFor(B).available())
                    .isGreaterThan(3);
            assertThat(ids(catalogs.get(A).search(new CatalogSearch("mug", 3)))).containsExactly("item_456");
        }
    }

    // Covers: I1, 13.1, S22, m6a-plan C11 (a purchase at B makes an order at B and none at A or C; the order counters are
    // the merchants' own, so the first order at A has the same number as the first at B)
    @Test
    void orderCounters_areNotShared() throws Exception {
        try (Lane lane = Lane.start(0, key(), dir, 3);
             AgentStack stack = AgentStack.forLane(lane, lane.newDatabase("orders"), SCALE, 1,
                     new SequentialIdGenerator("ord"), Scenarios.PAY_OK)) {
            buy(stack, B, "b_mug_01", "alice");

            assertThat(lane.groundTruth(1).orders()).hasSize(1);
            assertThat(lane.groundTruth(0).orders()).isEmpty();
            assertThat(lane.groundTruth(2).orders()).isEmpty();

            buy(stack, A, "item_456", "bob");

            assertThat(lane.groundTruth(0).orders()).hasSize(1);
            assertThat(lane.groundTruth(1).orders()).hasSize(1);
            assertThat(lane.groundTruth(2).orders()).isEmpty();
            assertThat(lane.groundTruth(0).orders().getFirst().id())
                    .isEqualTo(lane.groundTruth(1).orders().getFirst().id());
        }
    }

    // Covers: 13.1, m6a-plan C11 (every row of M4 and M5 is a lane of one with one user and no rules at another merchant, so
    // its run is what it was; a row that names more than a lane holds is refused)
    @Test
    void everyExistingRow_isALaneOfOne_andABadMultiIsRefused() {
        List<String> before = List.of("S0", "S1", "S2", "S3", "S4", "S5a", "S5b", "S6", "S7", "S8", "S9", "S11", "S13", "S14",
                "S28", "A-S0", "A-S2", "A-S4", "A-S10", "A-S11", "A-S12", "A-B");
        assertThat(Scenarios.all().stream().filter(s -> before.contains(s.id())).toList()).hasSize(before.size())
                .allSatisfy(s -> assertThat(s.multi()).isEqualTo(Scenario.Multi.ONE));
        assertThat(Scenario.Multi.ONE.merchants()).isEqualTo(1);
        assertThat(Scenario.Multi.ONE.users()).isEqualTo(1);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Scenario.Multi(4, 1, java.util.Map.of()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Scenario.Multi(2, 0, java.util.Map.of()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Scenario.Multi(2, 1, java.util.Map.of(2, (t, run) -> RuleSet.empty())));
    }

    /** Measured on this Mac at C11 (see notes/m6a/m6a-progress4.md); the bounds leave room for a slower runner. */
    static final Duration START_BOUND = Duration.ofSeconds(5);
    static final long MEMORY_BOUND_MB = 64;
    /** What {@code -Peval-full} starts: 16 lanes, each of three merchants and three proxies. */
    static final int LANES = 16;

    // Covers: 13.1, m6a-plan C11 and risk 8 (16 lanes of three, which is 48 merchants and 48 proxies in one JVM, start
    // within a bound and hold little heap)
    @Test
    void sixteenLanesOfThree_startQuickly_andHoldLittleMemory() throws Exception {
        System.gc();
        long before = usedBytes();
        long t0 = System.nanoTime();
        List<Lane> lanes = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < LANES; i++) {
                lanes.add(Lane.start(i, key(), dir, 3));
            }
            Duration took = Duration.ofNanos(System.nanoTime() - t0);
            System.gc();
            long mb = Math.max(0, usedBytes() - before) / (1024 * 1024);
            System.out.println("MEASURED " + LANES + " lanes of three (" + LANES * 3 + " merchants, " + LANES * 3
                    + " proxies): start " + took.toMillis() + " ms, heap about " + mb + " MB, threads "
                    + Thread.activeCount());
            assertThat(lanes).allSatisfy(l -> assertThat(l.merchantCount()).isEqualTo(3));
            assertThat(took).isLessThan(START_BOUND);
            assertThat(mb).isLessThan(MEMORY_BOUND_MB);
        } finally {
            lanes.forEach(Lane::close);
        }
    }

    private static long usedBytes() {
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }
}
