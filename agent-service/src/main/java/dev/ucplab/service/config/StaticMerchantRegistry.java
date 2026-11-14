package dev.ucplab.service.config;

import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.MerchantRegistry;
import dev.ucplab.ucp.UcpHttpGateway;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * M6's registry: one merchant, configured directly (25.4; {@code MERCHANTS_FILE} arrives with M6a). The profile is
 * the gateway's discovery, which the gateway itself caches (8.5); this class keeps nothing.
 */
public final class StaticMerchantRegistry implements MerchantRegistry {

    private final MerchantEntry entry;
    private final UcpHttpGateway gateway;

    public StaticMerchantRegistry(MerchantEntry entry, UcpHttpGateway gateway) {
        this.entry = Objects.requireNonNull(entry, "entry");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        if (!gateway.merchant().id().equals(entry.id())) {
            throw new IllegalArgumentException("the gateway is bound to another merchant");
        }
    }

    @Override
    public List<MerchantEntry> active() {
        return List.of(entry);
    }

    @Override
    public Optional<MerchantEntry> find(MerchantId id) {
        return entry.id().equals(id) ? Optional.of(entry) : Optional.empty();
    }

    @Override
    public MerchantProfile profile(MerchantId id) {
        if (!entry.id().equals(id)) {
            throw new IllegalArgumentException("unknown merchant");
        }
        return gateway.discover(id);
    }
}
