package dev.ucplab.core.policy;

import dev.ucplab.core.config.AgentProfile;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The settings of table 9.1 [CONTRACT], from {@code policy.yaml}. Amounts are minor units.
 *
 * @param caps P2 and P3 for each ISO 4217 currency, in its minor units (9.1 "Caps per currency"); at least one
 * @param maxQuantityPerLine P4
 * @param maxLines P4
 * @param maxProposalsPerHour P5
 * @param approvalRequired P6
 * @param maxRequotes P8
 */
// P7 has no setting: it is always on (9.1).
// agent-service's PolicyFile loads POLICY_FILE (policy.yaml) into this record, and ServiceProperties calls
// requireValidFor(AGENT_PROFILE) at startup. agent-core stays JDK-only, so no YAML parsing here (5.2).
public record PolicyConfig(
        Map<String, CurrencyCaps> caps,
        int maxQuantityPerLine,
        int maxLines,
        int maxProposalsPerHour,
        boolean approvalRequired,
        int maxRequotes) {

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    public PolicyConfig {
        Objects.requireNonNull(caps, "caps");
        if (caps.isEmpty()) {
            throw new IllegalArgumentException("caps must name at least one currency");
        }
        caps.forEach((currency, c) -> {
            if (!CURRENCY.matcher(currency).matches()) {
                throw new IllegalArgumentException("a cap's currency must be three upper-case letters: " + currency);
            }
            Objects.requireNonNull(c, "caps of " + currency);
        });
        caps = Map.copyOf(new TreeMap<>(caps));
        requirePositive(maxQuantityPerLine, "maxQuantityPerLine");
        requirePositive(maxLines, "maxLines");
        requirePositive(maxProposalsPerHour, "maxProposalsPerHour");
        if (maxRequotes < 0) {
            throw new IllegalArgumentException("maxRequotes must not be negative: " + maxRequotes);
        }
    }

    /** The USD caps {@code perPurchaseCapMinor} (P2) and {@code dailyCapMinor} (P3), with the other settings. */
    public PolicyConfig(long perPurchaseCapMinor, long dailyCapMinor, int maxQuantityPerLine, int maxLines,
                        int maxProposalsPerHour, boolean approvalRequired, int maxRequotes) {
        this(Map.of(USD, new CurrencyCaps(perPurchaseCapMinor, dailyCapMinor)), maxQuantityPerLine, maxLines,
                maxProposalsPerHour, approvalRequired, maxRequotes);
    }

    /** The currency of the default caps and of the older flat keys (9.1). */
    public static final String USD = "USD";

    /** The defaults of table 9.1: caps in USD only. */
    public static PolicyConfig defaults() {
        return new PolicyConfig(20_000, 50_000, 5, 5, 10, true, 2);
    }

    /** P2 and P3 for {@code currency}, or empty when none are set: a quote in it is refused, never converted (9.1). */
    public Optional<CurrencyCaps> capsFor(String currency) {
        return Optional.ofNullable(caps.get(Objects.requireNonNull(currency, "currency")));
    }

    /**
     * Startup check for the profile. P6: approval may not be switched off in prod.
     *
     * @throws IllegalStateException if the configuration is not allowed in {@code profile}
     */
    public PolicyConfig requireValidFor(AgentProfile profile) {
        Objects.requireNonNull(profile, "profile");
        if (profile == AgentProfile.PROD && !approvalRequired) {
            throw new IllegalStateException("P6: approvalRequired may not be false in the prod profile");
        }
        return this;
    }

    private static void requirePositive(long value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
    }
}
