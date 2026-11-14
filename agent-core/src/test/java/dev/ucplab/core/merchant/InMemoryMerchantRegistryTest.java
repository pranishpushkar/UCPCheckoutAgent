package dev.ucplab.core.merchant;

import java.util.List;

/** The in-memory {@link MerchantRegistry} runs the registry contract (H3 P3; 19, P1). */
class InMemoryMerchantRegistryTest extends MerchantRegistryContract {

    @Override
    protected MerchantRegistry create(List<MerchantEntry> entries) {
        return new InMemoryMerchantRegistry(entries);
    }
}
