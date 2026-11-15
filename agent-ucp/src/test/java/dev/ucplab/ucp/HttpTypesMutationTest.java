package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** H3 P8: the small records of the client (8.2, 8.3, I8), one test per surviving mutant. */
class HttpTypesMutationTest {

    // Covers: 8.2. Kills HttpReply.header:21 RemoveConditional (a header name with no value is absent, not an exception)
    @Test
    void aHeaderWithNoValue_isAbsent() {
        HttpReply reply = new HttpReply(200, Map.of("retry-after", List.of(), "x", List.of("1", "2")), new byte[0]);

        assertThat(reply.header("Retry-After")).isEmpty();
        assertThat(reply.header("X")).contains("1");
        assertThat(reply.header("missing")).isEmpty();
    }

    // Covers: I8 (a reply and a call print without their body or headers, which can carry credentials). Kills
    // HttpReply.toString:26 and HttpCall's
    @Test
    void theTextOfAReplyAndACall_showsNoBodyAndNoHeaders() {
        HttpReply reply = new HttpReply(402, Map.of(), "tok_secret".getBytes());
        HttpCall call = new HttpCall("POST", URI.create("http://h:1/p"), Map.of("Authorization", "Bearer tok_secret"),
                Optional.of(new FrozenBody("{\"token\":\"tok_secret\"}")), Duration.ofSeconds(1));

        assertThat(reply.toString()).isEqualTo("HttpReply[402, 10 bytes]");
        assertThat(call.toString()).isEqualTo("HttpCall[POST http://h:1/p]");
    }

    // Covers: 8.3 (a request timeout is positive). Kills HttpCall.<init>:24 RemoveConditional (both operands)
    @Test
    void aCallNeedsAPositiveTimeout() {
        for (Duration bad : new Duration[] {Duration.ZERO, Duration.ofMillis(-1)}) {
            assertThatThrownBy(() -> new HttpCall("GET", URI.create("http://h/"), Map.of(), Optional.empty(), bad))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("timeout must be positive");
        }
        assertThat(new HttpCall("GET", URI.create("http://h/"), Map.of(), Optional.empty(), Duration.ofNanos(1)).timeout())
                .isEqualTo(Duration.ofNanos(1));
    }

    private static final IdempotencyKey KEY = new IdempotencyKey("11111111-2222-8333-8444-555555555555");

    // Covers: 8.4, I7 (every mutating call needs an Idempotency-Key; a read does not). Kills ApiCall.<init>:34
    @Test
    void aMutatingCallNeedsAKey_aReadDoesNot() {
        URI uri = URI.create("http://h:1/p");
        for (AttemptOp op : new AttemptOp[] {AttemptOp.CREATE, AttemptOp.UPDATE, AttemptOp.COMPLETE, AttemptOp.CANCEL}) {
            assertThatThrownBy(() -> new ApiCall(op, "POST", uri, Optional.empty(), Optional.empty(), false))
                    .as(op.name()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Idempotency-Key");
            assertThat(new ApiCall(op, "POST", uri, Optional.of(KEY), Optional.empty(), false).key()).contains(KEY);
        }
        assertThat(new ApiCall(AttemptOp.GET, "GET", uri, Optional.empty(), Optional.empty(), false).key()).isEmpty();
    }

    // Covers: 8.3 (the circuit breaker and the limiter are keyed on host:port; a missing port is the scheme's). Kills
    // ApiCall.hostPort:41 (the three-way choice)
    @Test
    void theHostPort_isTheExplicitPort_orTheSchemesDefault() {
        assertThat(call("http://m.example/p").hostPort()).isEqualTo("m.example:80");
        assertThat(call("https://m.example/p").hostPort()).isEqualTo("m.example:443");
        assertThat(call("https://m.example:8443/p").hostPort()).isEqualTo("m.example:8443");
        assertThat(call("http://m.example:8081/p").hostPort()).isEqualTo("m.example:8081");
    }

    private static ApiCall call(String uri) {
        return new ApiCall(AttemptOp.GET, "GET", URI.create(uri), Optional.empty(), Optional.empty(), false);
    }
}
