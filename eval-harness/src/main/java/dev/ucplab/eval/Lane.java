package dev.ucplab.eval;

import dev.ucplab.faultproxy.FaultProxy;
import dev.ucplab.merchant.LabMerchant;
import java.io.IOException;
import java.net.URI;
import java.util.Objects;

/**
 * One lane of the evaluation (m4-plan section 4): its own in-process merchant on its own port and its own fault proxy in
 * front of it, so runs in different lanes share nothing. Runs inside a lane are sequential. The proxy gets the
 * evaluation's {@code bodyHmac} key at start-up (§12 Body hash), never through its admin API.
 */
public final class Lane implements AutoCloseable {

    private final int index;
    private final LabMerchant merchant;
    private final FaultProxy proxy;
    private final GroundTruth groundTruth;

    private Lane(int index, LabMerchant merchant, FaultProxy proxy) {
        this.index = index;
        this.merchant = merchant;
        this.proxy = proxy;
        this.groundTruth = new GroundTruth(merchant);
    }

    /** Starts lane {@code index}'s merchant, then its proxy, handing the proxy {@code hmacKey} (32 bytes). */
    public static Lane start(int index, byte[] hmacKey) throws IOException {
        Objects.requireNonNull(hmacKey, "hmacKey");
        if (index < 0) {
            throw new IllegalArgumentException("lane index must not be negative: " + index);
        }
        LabMerchant merchant = LabMerchant.start();
        try {
            return new Lane(index, merchant, FaultProxy.start(merchant.baseUrl(), 0, hmacKey));
        } catch (IOException | RuntimeException e) {
            merchant.close();
            throw e;
        }
    }

    public int index() {
        return index;
    }

    /** Ground-truth reads and lab controls, straight to the merchant. */
    public LabMerchant merchant() {
        return merchant;
    }

    public GroundTruth groundTruth() {
        return groundTruth;
    }

    public FaultProxy proxy() {
        return proxy;
    }

    /** Where the agent under test is pointed: the proxy, never the merchant itself. */
    public URI agentFacingUrl() {
        return proxy.baseUrl();
    }

    @Override
    public void close() {
        proxy.close();
        merchant.close();
    }
}
