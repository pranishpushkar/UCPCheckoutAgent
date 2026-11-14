package dev.ucplab.service.lab;

import dev.ucplab.core.approval.ApprovalRecord;
import dev.ucplab.core.approval.ApprovalRepository;
import dev.ucplab.core.approval.SnapshotLine;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentQueries;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.json.CanonicalJson;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * The lab's fault controls (23.3, 24.3, m6-plan section 6), local profile only. Four presets, and a rule set of the
 * caller's own in the proxy's section 12 format. Rules go to the one proxy of M6's one merchant, so they are the lab's,
 * not per user: that is why {@code demo} refuses to start until M6b has per-user sandboxes (D10).
 *
 * <p>The 503 preset's window ends after {@link #WINDOW}: {@link #sweep} removes the rule once the injected clock says
 * so, unless something else has replaced the rules since (each install bumps a version, and a window removes only its
 * own). Nothing here holds a lock; the version and the window move by compare-and-set.
 */
@Service
@Profile("local")
public class LabControls {

    /** How long the 503 preset lasts (24.3). */
    public static final Duration WINDOW = Duration.ofSeconds(20);
    /** The proxy's admin path for the {@code complete} call (section 12, 8.4). */
    static final String COMPLETE_PATH = "/ucp/v1/checkout-sessions/*/complete";

    /** The presets of 24.3, by their API names. */
    public enum Preset {
        DROP_NEXT_COMPLETE("drop_next_complete"),
        CHANGE_PRICE("change_price"),
        MERCHANT_503("merchant_503_20s"),
        MERCHANT_DOWN("merchant_down");

        private final String api;

        Preset(String api) {
            this.api = api;
        }

        public String api() {
            return api;
        }

        public static Optional<Preset> parse(String name) {
            for (Preset p : values()) {
                if (p.api.equals(name)) {
                    return Optional.of(p);
                }
            }
            return Optional.empty();
        }
    }

    /** A request the lab cannot act on, with a fixed message. */
    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message, null, false, false);
        }
    }

    public static final String NO_INTENT = "no intent is awaiting approval";

    private record Window(long version, Instant until) {
    }

    private final ProxyAdmin proxy;
    private final MerchantLab merchant;
    private final IntentQueries queries;
    private final ApprovalRepository approvals;
    private final Clock clock;
    private final AtomicLong version = new AtomicLong();
    private final AtomicReference<Window> window = new AtomicReference<>();

    public LabControls(ProxyAdmin proxy, MerchantLab merchant, IntentQueries queries, ApprovalRepository approvals,
                       Clock clock) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.merchant = Objects.requireNonNull(merchant, "merchant");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Installs the caller's own rule set (section 12 format); the proxy validates it. Returns the rule ids. */
    public List<String> replaceRules(Map<String, Object> ruleSet) {
        Objects.requireNonNull(ruleSet, "ruleSet");
        install(CanonicalJson.write(ruleSet));
        List<String> ids = new ArrayList<>();
        if (ruleSet.get("rules") instanceof List<?> rules) {
            for (Object rule : rules) {
                if (rule instanceof Map<?, ?> m && m.get("id") instanceof String id) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /** Applies a preset for {@code auth}; the rule ids it installed (none for {@code change_price}). */
    public List<String> preset(AuthContext auth, Preset preset) {
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(preset, "preset");
        return switch (preset) {
            case DROP_NEXT_COMPLETE -> {
                install(ruleSet(rule("lab-drop-next-complete", Map.of("method", "POST", "pathPattern", COMPLETE_PATH),
                        Map.of("type", "forward_then_drop_response"), 1L)));
                yield List.of("lab-drop-next-complete");
            }
            case MERCHANT_503 -> {
                long v = version.incrementAndGet();
                window.set(null);
                proxy.replace(ruleSet(rule("lab-merchant-503", Map.of(),
                        Map.of("type", "fail_before_forward", "status", 503L, "retryAfterSeconds", 1L), null)));
                window.set(new Window(v, clock.instant().plus(WINDOW)));
                yield List.of("lab-merchant-503");
            }
            case MERCHANT_DOWN -> {
                install(ruleSet(rule("lab-merchant-down", Map.of(), Map.of("type", "fail_before_forward", "close", true),
                        null)));
                yield List.of("lab-merchant-down");
            }
            case CHANGE_PRICE -> {
                changePrice(auth);
                yield List.of();
            }
        };
    }

    /** Removes the rules, and any 503 window with them. */
    public void clearRules() {
        version.incrementAndGet();
        window.set(null);
        proxy.clear();
    }

    /** The merchant's own reset, then no rules. */
    public void reset() {
        merchant.reset();
        clearRules();
    }

    /** The merchant's ground-truth orders. */
    public List<Map<String, Object>> orders() {
        return merchant.orders();
    }

    /** Ends the 503 window when its time is up and nothing has replaced its rules since. */
    public void sweep() {
        Window w = window.get();
        if (w != null && !clock.instant().isBefore(w.until()) && window.compareAndSet(w, null)
                && version.get() == w.version()) {
            proxy.clear();
        }
    }

    private void install(String ruleSetJson) {
        version.incrementAndGet();
        window.set(null);
        proxy.replace(ruleSetJson);
    }

    /**
     * Raises the price, at the merchant, of the first item of the caller's newest intent awaiting approval by 1 minor
     * unit. The approve then meets {@code price_changed}, a re-quote and a new approval (P7). Only the caller's own
     * intents are looked at (T2).
     */
    private void changePrice(AuthContext auth) {
        PurchaseIntent newest = queries.page(auth.user(), Optional.of(IntentState.AWAITING_APPROVAL), Optional.empty(), 1)
                .intents().stream().findFirst().orElseThrow(() -> new Refused(NO_INTENT));
        IntentId id = newest.id();
        ApprovalRecord latest = approvals.findLatest(auth.user(), id).orElseThrow(() -> new Refused(NO_INTENT));
        SnapshotLine line = latest.snapshot().lines().getFirst();
        merchant.setPrice(line.itemId(), line.unitPriceMinor() + 1);
    }

    private static Map<String, Object> rule(String id, Map<String, Object> match, Map<String, Object> action,
                                            Long times) {
        Map<String, Object> rule = new java.util.LinkedHashMap<>();
        rule.put("id", id);
        rule.put("match", match);
        rule.put("action", action);
        if (times != null) {
            rule.put("times", times);
        }
        return rule;
    }

    private static String ruleSet(Map<String, Object> rule) {
        return CanonicalJson.write(Map.of("seed", 0L, "rules", List.of(rule)));
    }
}
