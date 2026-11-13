package dev.ucplab.runtime;

import dev.ucplab.core.intent.Orchestrator;
import dev.ucplab.core.merchant.CatalogGateway;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantRegistry;
import java.util.Map;
import java.util.Objects;

/**
 * The ports the tools call: the merchant registry, one catalog per merchant, and the orchestrator. The tools hold no
 * {@code ApprovalService} and no {@code MerchantGateway}, so none can approve, complete or cancel (I6, 9.3 L1).
 */
public record ToolPorts(MerchantRegistry merchants, Map<MerchantId, CatalogGateway> catalogs, Orchestrator orchestrator) {
    public ToolPorts {
        Objects.requireNonNull(merchants, "merchants");
        catalogs = Map.copyOf(Objects.requireNonNull(catalogs, "catalogs"));
        Objects.requireNonNull(orchestrator, "orchestrator");
    }
}
