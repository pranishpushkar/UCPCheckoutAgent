package dev.ucplab.service.config;

import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.MerchantRegistry;
import dev.ucplab.core.merchant.MerchantRegistryContract;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.service.FakeClock;
import dev.ucplab.ucp.UcpHttpGateway;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import dev.ucplab.core.merchant.MerchantGateway;

/** {@link ConfiguredMerchantRegistry} runs the registry contract (H3 P3; 19, P1, Appendix B), several merchants and all. */
class ConfiguredMerchantRegistryContractTest extends MerchantRegistryContract {

    @Override
    protected MerchantRegistry create(List<MerchantEntry> entries) {
        List<MerchantSettings> settings = entries.stream().map(e -> new MerchantSettings(e.id(), e.displayName(),
                e.baseUrl(), e.status(), Optional.empty(), Set.of(), MerchantSettings.DEFAULT_VERSIONS, Optional.empty()))
                .toList();
        MerchantGateway ok = (MerchantGateway) Proxy.newProxyInstance(MerchantGateway.class.getClassLoader(),
                new Class<?>[] {MerchantGateway.class}, (proxy, method, args) -> new MerchantProfile(
                        (dev.ucplab.core.merchant.MerchantId) args[0], "2026-08-25", URI.create("http://127.0.0.1:9101/ucp/v1"),
                        Set.of(UcpHttpGateway.CHECKOUT_CAPABILITY), Set.of(UcpHttpGateway.PAYMENT_HANDLER)));
        return ConfiguredMerchantRegistry.start(settings, id -> ok, new FakeClock(Instant.parse("2026-11-15T10:00:00Z")));
    }
}
