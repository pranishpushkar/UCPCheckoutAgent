package dev.ucplab.eval.check;

import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.orchestrator.OrchestratorConfig;
import dev.ucplab.core.recovery.RecoveryConfig;
import dev.ucplab.eval.TimeScale;
import dev.ucplab.ucp.UcpConfig;
import java.time.Duration;
import java.util.Objects;

/**
 * What the checks need besides the evidence: the run's configuration (for the 7.6 bounds of I5), the evaluation's
 * {@code bodyHmac} key (for the I7 cross-check against the agent's frozen bodies), the merchant every lane serves (I2:
 * same merchant), how often the harness looked (its observations are late by at most that), and how many times the
 * agent process was killed during the run (each crash extends a 7.6 bound by one lease plus one worker period).
 *
 * <p>The key never leaves this object: {@link #toString()} leaves it out and {@link #hmacKey()} returns a copy.
 */
public final class CheckContext {

    private static final String DURATIONS_ONLY = "profile=\"https://eval.ucplab.dev/check\"";

    private final UcpConfig ucp;
    private final OrchestratorConfig orchestrator;
    private final RecoveryConfig recovery;
    private final byte[] hmacKey;
    private final MerchantId merchant;
    private final Duration harnessPoll;
    private final int crashes;

    public CheckContext(UcpConfig ucp, OrchestratorConfig orchestrator, RecoveryConfig recovery, byte[] hmacKey,
                        MerchantId merchant, Duration harnessPoll, int crashes) {
        this.ucp = Objects.requireNonNull(ucp, "ucp");
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        Objects.requireNonNull(hmacKey, "hmacKey");
        if (hmacKey.length != 32) {
            throw new IllegalArgumentException("the bodyHmac key must be 32 bytes: " + hmacKey.length);
        }
        this.hmacKey = hmacKey.clone();
        this.merchant = Objects.requireNonNull(merchant, "merchant");
        this.harnessPoll = Objects.requireNonNull(harnessPoll, "harnessPoll");
        if (crashes < 0) {
            throw new IllegalArgumentException("crashes must not be negative: " + crashes);
        }
        this.crashes = crashes;
    }

    /** The context of an in-process run at time scale {@code scale}, with no crash. */
    public static CheckContext of(TimeScale scale, byte[] hmacKey, MerchantId merchant, Duration harnessPoll) {
        return new CheckContext(scale.ucp(DURATIONS_ONLY), scale.orchestrator(), scale.recovery(), hmacKey, merchant,
                harnessPoll, 0);
    }

    public CheckContext withCrashes(int n) {
        return new CheckContext(ucp, orchestrator, recovery, hmacKey, merchant, harnessPoll, n);
    }

    public UcpConfig ucp() {
        return ucp;
    }

    public OrchestratorConfig orchestrator() {
        return orchestrator;
    }

    public RecoveryConfig recovery() {
        return recovery;
    }

    byte[] hmacKey() {
        return hmacKey.clone();
    }

    public MerchantId merchant() {
        return merchant;
    }

    public Duration harnessPoll() {
        return harnessPoll;
    }

    public int crashes() {
        return crashes;
    }

    @Override
    public String toString() {
        return "CheckContext[merchant=" + merchant.value() + ", harnessPoll=" + harnessPoll + ", crashes=" + crashes
                + ']';
    }
}
