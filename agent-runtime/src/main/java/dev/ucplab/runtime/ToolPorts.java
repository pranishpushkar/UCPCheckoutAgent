package dev.ucplab.runtime;

import dev.ucplab.core.intent.Orchestrator;
import dev.ucplab.core.merchant.CatalogGateway;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * The ports the tools call: the merchant registry, one catalog per merchant, and the orchestrator. The tools hold no
 * {@code ApprovalService} and no {@code MerchantGateway}, so none can approve, complete or cancel (I6, 9.3 L1).
 *
 * @param perMerchant how long a search waits for each merchant (6.2, 19); the merchants are asked at once, so the whole
 *     search waits at most this long
 */
public record ToolPorts(MerchantRegistry merchants, Map<MerchantId, CatalogGateway> catalogs, Orchestrator orchestrator,
                        Duration perMerchant) {

    /** 6.2: a search asks each merchant for 2 s at most. */
    public static final Duration DEFAULT_PER_MERCHANT = Duration.ofSeconds(2);

    public ToolPorts {
        Objects.requireNonNull(merchants, "merchants");
        catalogs = Map.copyOf(Objects.requireNonNull(catalogs, "catalogs"));
        Objects.requireNonNull(orchestrator, "orchestrator");
        Objects.requireNonNull(perMerchant, "perMerchant");
        if (perMerchant.isZero() || perMerchant.isNegative()) {
            throw new IllegalArgumentException("perMerchant must be positive");
        }
    }

    public ToolPorts(MerchantRegistry merchants, Map<MerchantId, CatalogGateway> catalogs, Orchestrator orchestrator) {
        this(merchants, catalogs, orchestrator, DEFAULT_PER_MERCHANT);
    }

    /** The same ports with another limit for each merchant, for a test that cannot wait 2 s. */
    public ToolPorts withPerMerchant(Duration limit) {
        return new ToolPorts(merchants, catalogs, orchestrator, limit);
    }
}
