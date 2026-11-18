package dev.ucplab.core.orchestrator;

import dev.ucplab.core.merchant.ContinueUrl;
import dev.ucplab.core.merchant.MerchantEntry;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;

/**
 * The orchestrator's half of I12 (4.3, 26.5): the checks on the registry entry, its fingerprint and a continue_url. The
 * gateway's half (its endpoint host against shop_domain, 8.5) lives in the gateway. Nothing here does I/O; every check
 * reads values the caller already holds.
 */
final class OwnStoreGuard {

    /** The 7.2 event and failure_reason: a mutating step at a merchant whose entry fails I12. */
    static final String MERCHANT_NOT_OWNED = "merchant_not_owned";
    /** The 7.2 event and failure_reason: the entry's fingerprint differs from the one the intent stored. */
    static final String MERCHANT_CHANGED = "merchant_changed";
    /** The 7.6 event: an owed cancel not sent, because the entry fails I12 or changed. */
    static final String CANCEL_BLOCKED = "cancel_blocked_i12";
    /** The rule id of the propose-time denial, reported through policy_denied (7.2). */
    static final String RULE = "I12";
    /** The reason in the propose-time policy_decision event; fixed, it names nothing. */
    static final String NOT_OWNED_REASON = "merchant entry is not owned (I12)";
    /** The fingerprint stored for a merchant the registry does not list: never equal to an entry's. */
    static final String NO_ENTRY = "{}";

    private OwnStoreGuard() {
    }

    /**
     * Whether a create, update, complete or cancel may go to this merchant: its entry says {@code owned: true}. An MCP
     * entry always has a shop_domain ({@link MerchantEntry}'s constructor refuses one without), so owned is the whole
     * entry check; the endpoint host is the gateway's.
     */
    static boolean entryAllows(Optional<MerchantEntry> entry) {
        return entry.map(MerchantEntry::owned).orElse(false);
    }

    /** Whether the entry now is the one the intent was proposed under (10, 19); no entry is a change. */
    static boolean unchanged(Optional<MerchantEntry> entry, String storedFingerprint) {
        return entry.map(e -> e.fingerprint().equals(storedFingerprint)).orElse(false);
    }

    /**
     * Whether a continue_url may be used (I12): {@code https}, no user info, the host exactly the entry's shop_domain,
     * and no port but 443. A path-only or opaque URL has no host and is refused, as is any URL at a merchant with no
     * shop_domain or an entry that fails {@link #entryAllows}.
     */
    static boolean continueUrlAllowed(Optional<MerchantEntry> entry, ContinueUrl url) {
        if (!entryAllows(entry)) {
            return false;
        }
        Optional<String> shop = entry.flatMap(MerchantEntry::shopDomain);
        URI u = url.value();
        String host = u.getHost();
        return shop.isPresent()
                && "https".equalsIgnoreCase(u.getScheme())
                && u.getRawUserInfo() == null
                && host != null
                && host.toLowerCase(Locale.ROOT).equals(shop.get())
                && (u.getPort() == -1 || u.getPort() == 443);
    }
}
