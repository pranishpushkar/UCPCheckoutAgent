package dev.ucplab.core.merchant.fake;

import dev.ucplab.core.merchant.CatalogGateway;
import dev.ucplab.core.merchant.CatalogGatewayContract;
import java.util.List;

/** The in-memory {@link CatalogGateway} runs the catalog contract (H3 P3; FR1, 6.2, S10). */
class InMemoryCatalogTest extends CatalogGatewayContract {

    @Override
    protected CatalogGateway labCatalog() {
        return new InMemoryCatalog(LAB_CATALOG);
    }

    @Override
    protected CatalogGateway hostileCatalog() {
        return new InMemoryCatalog(List.of(HOSTILE));
    }
}
