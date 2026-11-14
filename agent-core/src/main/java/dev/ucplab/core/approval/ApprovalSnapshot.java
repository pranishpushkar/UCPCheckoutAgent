package dev.ucplab.core.approval;

import dev.ucplab.core.crypto.Sha256;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.SessionId;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What the user approves. Contract 9.2, invariants I2 and I3. Built from the merchant's
 * response, never from model output. {@code fulfillment} is the chosen shipping option, when the merchant needed one
 * (D4); the hash covers it, and the street address is never part of it.
 */
public record ApprovalSnapshot(
        IntentId intentId,
        MerchantId merchantId,
        SessionId sessionId,
        List<SnapshotLine> lines,
        String currency,
        long totalMinor,
        long maxTotalMinor,
        Instant expiresAt,
        Optional<FulfillmentSnapshot> fulfillment) {

    /** A snapshot with no shipping part: the lab merchant, and any merchant that needs none. */
    public ApprovalSnapshot(
            IntentId intentId, MerchantId merchantId, SessionId sessionId, List<SnapshotLine> lines, String currency,
            long totalMinor, long maxTotalMinor, Instant expiresAt) {
        this(intentId, merchantId, sessionId, lines, currency, totalMinor, maxTotalMinor, expiresAt, Optional.empty());
    }

    private static final Comparator<SnapshotLine> LINE_ORDER =
            Comparator.comparing(SnapshotLine::itemId)
                    .thenComparingInt(SnapshotLine::quantity)
                    .thenComparingLong(SnapshotLine::unitPriceMinor);

    public ApprovalSnapshot {
        Objects.requireNonNull(intentId, "intentId");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(fulfillment, "fulfillment");
        lines = List.copyOf(lines);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("a snapshot needs at least one line");
        }
        if (currency.isBlank()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
        if (totalMinor < 0) {
            throw new IllegalArgumentException("totalMinor must not be negative: " + totalMinor);
        }
        if (maxTotalMinor < totalMinor) {
            throw new IllegalArgumentException(
                    "maxTotalMinor " + maxTotalMinor + " is below totalMinor " + totalMinor);
        }
    }

    /**
     * The canonical JSON form that is hashed: field names as in 9.2, lines sorted by itemId, then
     * quantity, then unitPriceMinor, expiresAt as ISO-8601 UTC.
     */
    public String canonicalJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("intentId", intentId.value());
        m.put("merchantId", merchantId.value());
        m.put("sessionId", sessionId.value());
        m.put("lines", lines.stream().sorted(LINE_ORDER).map(ApprovalSnapshot::lineJson).toList());
        m.put("currency", currency);
        m.put("totalMinor", totalMinor);
        m.put("maxTotalMinor", maxTotalMinor);
        m.put("expiresAt", DateTimeFormatter.ISO_INSTANT.format(expiresAt));
        // Only when there is a shipping part, so a snapshot without one hashes exactly as before (9.2, D4).
        fulfillment.ifPresent(f -> m.put("fulfillment", fulfillmentJson(f)));
        return CanonicalJson.write(m);
    }

    /** {@code snapshotHash = sha256_hex(canonicalJson(snapshot))} (9.2). */
    public String hash() {
        return Sha256.hex(canonicalJson());
    }

    private static Map<String, Object> fulfillmentJson(FulfillmentSnapshot f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("optionId", f.optionId());
        m.put("label", f.label());
        m.put("priceMinor", f.priceMinor());
        f.destinationCountry().ifPresent(c -> m.put("destinationCountry", c));
        return m;
    }

    private static Map<String, Object> lineJson(SnapshotLine line) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemId", line.itemId());
        m.put("quantity", line.quantity());
        m.put("unitPriceMinor", line.unitPriceMinor());
        return m;
    }
}
