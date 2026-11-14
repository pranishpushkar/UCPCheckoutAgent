package dev.ucplab.core.merchant;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Helpers for the gateway-by-merchant function the orchestrator takes (8.5: one gateway per merchant). */
public final class MerchantGateways {

    private MerchantGateways() {
    }

    /** The gateway of each merchant in {@code byMerchant}; any other merchant throws {@link NoGatewayException}. */
    public static Function<MerchantId, MerchantGateway> of(Map<MerchantId, ? extends MerchantGateway> byMerchant) {
        Map<MerchantId, MerchantGateway> copy = Map.copyOf(Objects.requireNonNull(byMerchant, "byMerchant"));
        return id -> {
            MerchantGateway gateway = copy.get(Objects.requireNonNull(id, "id"));
            if (gateway == null) {
                throw new NoGatewayException(id);
            }
            return gateway;
        };
    }
}
