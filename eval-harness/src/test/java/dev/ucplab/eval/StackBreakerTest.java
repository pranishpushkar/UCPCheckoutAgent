package dev.ucplab.eval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.CatalogSearch;
import dev.ucplab.faultproxy.LogEntry;
import dev.ucplab.faultproxy.RuleSet;
import dev.ucplab.ucp.CircuitBreaker;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An agent stack builds one breaker registry (8.3, 19, m6a-plan C4): a merchant whose catalog calls fail is not sent a
 * create either, so S20's "only the failing merchant's breaker opens" can be read from the stack.
 */
class StackBreakerTest {

    private static final TimeScale SCALE = TimeScale.of(4);

    @TempDir
    Path dir;

    // Covers: 8.3, 19, S20 (five catalog failures through the lane's proxy open the stack's one breaker for the merchant,
    // and the proposal that follows sends no create)
    @Test
    void catalogFailures_openTheBreakerTheOrchestratorUses() throws Exception {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        try (Lane lane = Lane.start(0, key, dir);
             AgentStack stack = new AgentStack(lane.agentFacingUrl(), lane.newDatabase("breaker"), SCALE, 1,
                     new SequentialIdGenerator("brk"), Scenarios.PAY_OK)) {
            lane.proxy().install(RuleSet.parse("{\"seed\":1,\"rules\":[{\"id\":\"catalog-down\",\"match\":"
                    + "{\"pathPattern\":\"/ucp/v1/catalog/*\"},\"action\":{\"type\":\"fail_before_forward\","
                    + "\"status\":503}}]}"));
            var catalog = stack.toolPorts().catalogs().get(Scenarios.SHOP);
            catalog.search(new CatalogSearch("mug", 3));
            catalog.search(new CatalogSearch("mug", 3));

            stack.orchestrator.propose(new AuthContext(new UserId("u"), "u@eval.example", stack.clock.instant(), "s"),
                    Scenarios.SHOP, List.of(new ProposedLine("item_456", 1)), "breaker test");

            assertThat(lane.proxy().log(0)).extracting(LogEntry::path).noneMatch(p -> p.endsWith("/checkout-sessions"));
            assertThat(stack.breakers().forMerchant(Scenarios.SHOP).state()).isEqualTo(CircuitBreaker.State.OPEN);
        }
    }
}
