package dev.ucplab.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.CatalogSearch;
import dev.ucplab.core.orchestrator.DefaultOrchestrator;
import dev.ucplab.faultproxy.LogEntry;
import dev.ucplab.service.config.ServiceProperties;
import dev.ucplab.service.lab.LabControls;
import dev.ucplab.ucp.CatalogClient;
import dev.ucplab.ucp.CircuitBreaker;
import dev.ucplab.ucp.CircuitBreakers;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One breaker per merchant, shared by its catalog client and its checkout gateway (8.3, 19, m6a-plan C4): the service
 * builds one {@link CircuitBreakers} for both, so a merchant that fails its catalog calls is also not sent a quote.
 */
class SharedBreakerTest {

    @TempDir
    Path dir;

    private TestService service;

    @AfterEach
    void stop() {
        if (service != null) {
            service.close();
        }
    }

    // Covers: 8.3, 19, S20 (five failed catalog calls at the merchant open the breaker that checkout uses: a proposal
    // after them sends no create to the merchant, and the one shared breaker is OPEN)
    @Test
    void catalogFailures_openTheBreakerThatCheckoutUses() throws IOException {
        service = TestService.start(dir);
        service.bean(LabControls.class).replaceRules(Map.of("seed", 1L, "rules", List.of(Map.of(
                "id", "catalog-down", "match", Map.of("pathPattern", "/ucp/v1/catalog/*"),
                "action", Map.of("type", "fail_before_forward", "status", 503L)))));
        CatalogClient catalog = service.bean(CatalogClient.class);
        // Four attempts a call (the first and three retries): the second call's first attempt is the fifth failure.
        catalog.search(new CatalogSearch("mug", 3));
        catalog.search(new CatalogSearch("mug", 3));
        long mark = service.proxy.log(0).size();

        Clock clock = service.bean(Clock.class);
        AuthContext alice = new AuthContext(new UserId("local-alice"), "alice@local.ucplab.dev", clock.instant(), "s");
        service.bean(DefaultOrchestrator.class).propose(alice, ServiceProperties.MERCHANT,
                List.of(new ProposedLine("item_456", 1)), "breaker test");

        assertThat(service.proxy.log(0)).extracting(LogEntry::path).as("everything the merchant was sent")
                .noneMatch(p -> p.endsWith("/checkout-sessions"));
        assertThat(mark).isPositive();
        assertThat(service.bean(CircuitBreakers.class).forMerchant(ServiceProperties.MERCHANT).state())
                .isEqualTo(CircuitBreaker.State.OPEN);
    }
}
