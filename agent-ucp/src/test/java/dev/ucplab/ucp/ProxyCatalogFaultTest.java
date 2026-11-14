package dev.ucplab.ucp;

import static dev.ucplab.ucp.ProxyFixture.rule;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CatalogPage;
import dev.ucplab.core.merchant.CatalogSearch;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.faultproxy.Action;
import dev.ucplab.faultproxy.LogEntry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.SplittableRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Catalog reads through the real fault proxy in front of the real merchant (section 14, level 4, from M4): the retry
 * rules of {@code get} (8.2) and the invalid-body rule (8.1) hold for a POST read, and a read never carries an
 * Idempotency-Key (8.4). These replace the 429, 500, drop and garbled cases of {@link CatalogStubFaultTest}.
 */
class ProxyCatalogFaultTest extends ContractTestBase {

    private static final String SEARCH = "/ucp/v1/catalog/search";
    private static final UcpConfig SHORT = new UcpConfig("profile=\"https://agent.example/.well-known/ucp\"",
            Duration.ofSeconds(2), Duration.ofMillis(300), Duration.ofMillis(400), 3, Duration.ofMillis(200),
            Duration.ofSeconds(2), 5, Duration.ofSeconds(30), 1 << 20);

    private ProxyFixture proxy;
    private CatalogClient client;
    private final List<Duration> sleeps = new ArrayList<>();

    @BeforeEach
    void startProxy() throws Exception {
        proxy = new ProxyFixture(merchant.baseUrl());
        // The sleeper moves the clock too, so a 429's Retry-After that the rate limiter holds has passed when it retries.
        SleepClock clock = new SleepClock();
        UcpHttpGateway gateway = new UcpHttpGateway(new MerchantEntry(SHOP, proxy.baseUrl(), "Shop",
                MerchantStatus.ACTIVE), SHORT, new JdkHttpTransport(SHORT), new CircuitBreakers(SHORT, clock),
                clock, clock.recordingInto(sleeps), new SplittableRandom(5), new SequentialIdGenerator("s"));
        client = new CatalogClient(gateway, SHORT, new JdkHttpTransport(SHORT),
                new CircuitBreakers(SHORT, clock), clock, clock.recordingInto(sleeps), new SplittableRandom(5),
                new SequentialIdGenerator("c"));
        sleeps.clear();
    }

    @AfterEach
    void stopProxy() {
        proxy.close();
    }

    private static CatalogSearch mug() {
        return new CatalogSearch("blue mug", 3);
    }

    private List<LogEntry> searches() {
        List<LogEntry> searches = proxy.to("POST", SEARCH);
        assertThat(searches).allSatisfy(e -> assertThat(e.idempotencyKey()).isEmpty());
        assertThat(searches).extracting(e -> e.requestId().orElseThrow()).doesNotHaveDuplicates();
        return searches;
    }

    // Covers: 8.1, 8.2 (429 with a Retry-After within the cap: the client waits that long and retries, then succeeds)
    @Test
    void search_429WithShortRetryAfter_isRetried() {
        proxy.rules(rule("rate", "POST", SEARCH,
                new Action.FailBeforeForward(OptionalInt.of(429), false, OptionalInt.of(1))).withTimes(1));

        CallOutcome<CatalogPage> outcome = client.search(mug());

        assertThat(outcome).isInstanceOf(CallOutcome.Ok.class);
        assertThat(searches()).extracting(LogEntry::forwarded).containsExactly(false, true);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
        assertThat(merchant.orders()).isEmpty();
    }

    // Covers: 8.1, 8.2 (429 with a Retry-After beyond the cap: stop at once, ProtocolError carrying retryAfter)
    @Test
    void search_429WithLongRetryAfter_isProtocolErrorWithRetryAfter() {
        proxy.rules(rule("rate", "POST", SEARCH,
                new Action.FailBeforeForward(OptionalInt.of(429), false, OptionalInt.of(30))));

        CallOutcome<CatalogPage> outcome = client.search(mug());

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.ProtocolError.class, e -> {
            assertThat(e.httpStatus()).isEqualTo(429);
            assertThat(e.retryAfter()).contains(Duration.ofSeconds(30));
        });
        assertThat(searches()).hasSize(1);
        assertThat(sleeps).isEmpty();
        assertThat(merchant.orders()).isEmpty();
    }

    // Covers: 8.1, 8.2 (429 with no Retry-After backs off and retries up to 3 times, then ProtocolError)
    @Test
    void search_429Forever_stopsAfterFourAttempts() {
        proxy.rules(rule("rate", "POST", SEARCH, Action.FailBeforeForward.status(429)));

        CallOutcome<CatalogPage> outcome = client.search(mug());

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                e -> assertThat(e.httpStatus()).isEqualTo(429));
        assertThat(searches()).hasSize(4);
        assertThat(sleeps).hasSize(3);
        assertThat(merchant.orders()).isEmpty();
    }

    // Covers: 8.1, 8.2 (a 500 on a read is retried, and Uncertain when the retries run out)
    @Test
    void search_500Forever_isUncertainAfterFourAttempts() {
        proxy.rules(rule("boom", "POST", SEARCH, Action.FailBeforeForward.status(500)));

        assertThat(client.search(mug())).isInstanceOf(CallOutcome.Uncertain.class);

        assertThat(searches()).hasSize(4).noneMatch(LogEntry::forwarded);
        assertThat(merchant.orders()).isEmpty();
    }

    // Covers: 8.1, 8.2 (a 500 that clears is retried to success, and the success is the merchant's real answer)
    @Test
    void search_500ThenOk_isOk() {
        proxy.rules(rule("boom", "POST", SEARCH, Action.FailBeforeForward.status(500)).withTimes(2));

        CallOutcome<CatalogPage> outcome = client.search(mug());

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.Ok.class,
                ok -> assertThat(((CatalogPage) ok.value()).products()).extracting(p -> p.title())
                        .containsExactly("Blue Mug"));
        assertThat(searches()).extracting(LogEntry::forwarded).containsExactly(false, false, true);
        assertThat(merchant.orders()).isEmpty();
    }

    // Covers: 8.1, 8.2 (a dropped answer is retried like any read: four attempts, each answered by the merchant)
    @Test
    void search_dropped_isRetried_thenUncertain() {
        proxy.rules(rule("drop", "POST", SEARCH, new Action.ForwardThenDropResponse()));

        assertThat(client.search(mug())).isInstanceOf(CallOutcome.Uncertain.class);

        assertThat(searches()).hasSize(4)
                .allSatisfy(e -> assertThat(e.merchantStatus()).isEqualTo(OptionalInt.of(200)));
        assertThat(merchant.orders()).isEmpty();
    }

    // Covers: 8.1 (a garbled 200 body on a read is a ProtocolError invalid_response, not retried: the merchant answered)
    @Test
    void search_garbledBody_isProtocolError_notRetried() {
        proxy.rules(rule("garble", "POST", SEARCH, new Action.CorruptResponse(Action.CorruptResponse.Kind.GARBLE)));

        CallOutcome<CatalogPage> outcome = client.search(mug());

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.ProtocolError.class, e -> {
            assertThat(e.code()).isEqualTo("invalid_response");
            assertThat(e.httpStatus()).isEqualTo(200);
        });
        assertThat(searches()).hasSize(1);
        assertThat(merchant.orders()).isEmpty();
    }
}
