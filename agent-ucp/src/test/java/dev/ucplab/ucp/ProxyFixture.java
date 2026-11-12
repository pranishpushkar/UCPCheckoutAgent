package dev.ucplab.ucp;

import dev.ucplab.faultproxy.Action;
import dev.ucplab.faultproxy.FaultProxy;
import dev.ucplab.faultproxy.LogEntry;
import dev.ucplab.faultproxy.Match;
import dev.ucplab.faultproxy.PathPattern;
import dev.ucplab.faultproxy.Rule;
import dev.ucplab.faultproxy.RuleSet;
import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The real {@link FaultProxy} in front of the real merchant, for contract tests (section 14, level 4: from M4 the faults
 * the merchant cannot produce go through the proxy). The {@code bodyHmac} key is random per fixture and stays in this
 * test; it is passed to the proxy at start-up, never through the admin API.
 */
final class ProxyFixture implements AutoCloseable {

    static final String CREATE = "/ucp/v1/checkout-sessions";
    static final String SESSION = "/ucp/v1/checkout-sessions/*";
    static final String COMPLETE = "/ucp/v1/checkout-sessions/*/complete";
    private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(15);

    private final byte[] key = new byte[32];
    private final FaultProxy proxy;

    ProxyFixture(URI merchant) throws IOException {
        new SecureRandom().nextBytes(key);
        proxy = FaultProxy.start(merchant, 0, key);
    }

    URI baseUrl() {
        return proxy.baseUrl();
    }

    static Rule rule(String id, String method, String pathPattern, Action action) {
        return new Rule(id, Match.of(method, pathPattern), action);
    }

    /** Replaces the rule set (every counter starts again). */
    void rules(Rule... rules) {
        proxy.install(new RuleSet(1, List.of(rules)));
    }

    void clear() {
        proxy.clearRules();
    }

    /**
     * The whole log once every entry is settled, so a request still held at the proxy can neither be missed nor reach
     * the merchant after the test has looked. Polls with a bounded total wait.
     */
    List<LogEntry> settledLog() {
        long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
        while (true) {
            List<LogEntry> log = proxy.log(0);
            if (log.stream().allMatch(LogEntry::settled)) {
                return log;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("proxy log not settled within " + SETTLE_TIMEOUT + ": " + log);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /** The settled entries for {@code method} on paths matching {@code pathPattern}. */
    List<LogEntry> to(String method, String pathPattern) {
        PathPattern pattern = new PathPattern(pathPattern);
        return settledLog().stream().filter(e -> e.method().equals(method) && pattern.matches(e.path())).toList();
    }

    /** What the proxy logs as {@code bodyHmac} for these bytes. */
    String hmac(byte[] body) {
        Objects.requireNonNull(body, "body");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        proxy.close();
    }
}
