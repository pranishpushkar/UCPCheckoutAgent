package dev.ucplab.core.policy;

import dev.ucplab.core.config.AgentProfile;
import java.util.Objects;

/**
 * The settings of table 9.1 [CONTRACT], from {@code policy.yaml}. Amounts are minor units.
 *
 * @param perPurchaseCapMinor P2
 * @param dailyCapMinor P3
 * @param maxQuantityPerLine P4
 * @param maxLines P4
 * @param maxProposalsPerHour P5
 * @param approvalRequired P6
 * @param maxRequotes P8
 */
// P7 has no setting: it is always on (9.1).
// TODO(M6): agent-service loads POLICY_FILE (policy.yaml) into this record and calls
// requireValidFor(AGENT_PROFILE) at startup. agent-core stays JDK-only, so no YAML parsing here (5.2).
public record PolicyConfig(
        long perPurchaseCapMinor,
        long dailyCapMinor,
        int maxQuantityPerLine,
        int maxLines,
        int maxProposalsPerHour,
        boolean approvalRequired,
        int maxRequotes) {

    public PolicyConfig {
        requirePositive(perPurchaseCapMinor, "perPurchaseCapMinor");
        requirePositive(dailyCapMinor, "dailyCapMinor");
        requirePositive(maxQuantityPerLine, "maxQuantityPerLine");
        requirePositive(maxLines, "maxLines");
        requirePositive(maxProposalsPerHour, "maxProposalsPerHour");
        if (maxRequotes < 0) {
            throw new IllegalArgumentException("maxRequotes must not be negative: " + maxRequotes);
        }
    }

    /** The defaults of table 9.1. */
    public static PolicyConfig defaults() {
        return new PolicyConfig(20_000, 50_000, 5, 5, 10, true, 2);
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
