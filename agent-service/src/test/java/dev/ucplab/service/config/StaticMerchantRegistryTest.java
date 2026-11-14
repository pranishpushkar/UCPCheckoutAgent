package dev.ucplab.service.config;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantRegistry;
import dev.ucplab.core.merchant.MerchantRegistryContract;
import dev.ucplab.ucp.CircuitBreakers;
import dev.ucplab.ucp.JdkHttpTransport;
import dev.ucplab.ucp.Sleeper;
import dev.ucplab.ucp.UcpConfig;
import dev.ucplab.ucp.UcpHttpGateway;
import java.time.Clock;
import java.util.List;
import java.util.SplittableRandom;

/** {@link StaticMerchantRegistry} runs the registry contract (H3 P3; 19, P1). It holds one merchant, so it skips the multi-merchant test. */
class StaticMerchantRegistryTest extends MerchantRegistryContract {

    @Override
    protected MerchantRegistry create(List<MerchantEntry> entries) {
        assumeTrue(entries.size() == 1, "the static registry holds exactly one merchant");
        MerchantEntry entry = entries.get(0);
        UcpConfig ucp = UcpConfig.defaults("profile=\"https://agent.example/.well-known/ucp\"");
        UcpHttpGateway gateway = new UcpHttpGateway(entry, ucp, new JdkHttpTransport(ucp), new CircuitBreakers(ucp, Clock.systemUTC()),
                Clock.systemUTC(), Sleeper.THREAD, new SplittableRandom(1), new SequentialIdGenerator("r"));
        return new StaticMerchantRegistry(entry, gateway);
    }
}
