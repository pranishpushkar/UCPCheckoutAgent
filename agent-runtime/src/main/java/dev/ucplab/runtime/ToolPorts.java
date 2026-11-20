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
 *     search waits at most the longest of this and {@code catalogSource}
 * @param catalogSource how long a search waits for a catalog source ({@code transport: catalog}, 26.7) instead
 */
public record ToolPorts(MerchantRegistry merchants, Map<MerchantId, CatalogGateway> catalogs, Orchestrator orchestrator,
                        Duration perMerchant, Duration catalogSource) {

    /** 6.2: a search asks each merchant for 2 s at most. */
    public static final Duration DEFAULT_PER_MERCHANT = Duration.ofSeconds(2);
    /** 6.2, 26.7: and a catalog source for 5 s, the gateway's request timeout (8.3); not measured. */
    public static final Duration DEFAULT_CATALOG_SOURCE = Duration.ofSeconds(5);

    public ToolPorts {
        Objects.requireNonNull(merchants, "merchants");
        catalogs = Map.copyOf(Objects.requireNonNull(catalogs, "catalogs"));
        Objects.requireNonNull(orchestrator, "orchestrator");
        positive(perMerchant, "perMerchant");
        positive(catalogSource, "catalogSource");
    }

    public ToolPorts(MerchantRegistry merchants, Map<MerchantId, CatalogGateway> catalogs, Orchestrator orchestrator,
                     Duration perMerchant) {
        this(merchants, catalogs, orchestrator, perMerchant, DEFAULT_CATALOG_SOURCE);
    }

    public ToolPorts(MerchantRegistry merchants, Map<MerchantId, CatalogGateway> catalogs, Orchestrator orchestrator) {
        this(merchants, catalogs, orchestrator, DEFAULT_PER_MERCHANT);
    }

    /** The same ports with another limit for each merchant, for a test that cannot wait 2 s. */
    public ToolPorts withPerMerchant(Duration limit) {
        return new ToolPorts(merchants, catalogs, orchestrator, limit, catalogSource);
    }

    /** The same ports with another limit for a catalog source, for a test that cannot wait 5 s. */
    public ToolPorts withCatalogSource(Duration limit) {
        return new ToolPorts(merchants, catalogs, orchestrator, perMerchant, limit);
    }

    private static void positive(Duration d, String name) {
        Objects.requireNonNull(d, name);
        if (d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
