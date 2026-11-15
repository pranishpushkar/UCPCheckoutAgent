package dev.ucplab.ucp;

import static dev.ucplab.ucp.StubMerchant.reply;

import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.merchant.CatalogGateway;
import dev.ucplab.core.merchant.CatalogGatewayContract;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.merchant.MerchantHarness;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

/**
 * {@link CatalogClient} runs the catalog contract (H3 P3; FR1, 6.2, 8.1, S10): over the real lab merchant, started in
 * this JVM, for the reference catalog, and over a stub that serves one product whose description is hostile.
 */
class CatalogClientGatewayContractTest extends CatalogGatewayContract {

    private static final MerchantId SHOP = new MerchantId("shop-1");
    private static final UcpConfig CONFIG = UcpConfig.defaults("profile=\"https://agent.example/.well-known/ucp\"");
    private static MerchantHarness merchant;
    private final List<StubMerchant> stubs = new ArrayList<>();

    @BeforeAll
    static void startMerchant() throws Exception {
        merchant = MerchantHarness.start();
    }

    @AfterAll
    static void stopMerchant() {
        merchant.close();
    }

    @BeforeEach
    void resetMerchant() {
        merchant.reset();
    }

    @AfterEach
    void stopStubs() {
        stubs.forEach(StubMerchant::close);
    }

    private static CatalogClient client(URI baseUrl) {
        UcpHttpGateway gateway = new UcpHttpGateway(new MerchantEntry(SHOP, baseUrl, "Shop", MerchantStatus.ACTIVE), CONFIG,
                new JdkHttpTransport(CONFIG), new CircuitBreakers(CONFIG, Clock.systemUTC()), Clock.systemUTC(), d -> { },
                new SplittableRandom(3), new SequentialIdGenerator("c"));
        return new CatalogClient(gateway, CONFIG, new JdkHttpTransport(CONFIG), new CircuitBreakers(CONFIG, Clock.systemUTC()),
                Clock.systemUTC(), d -> { }, new SplittableRandom(3), new SequentialIdGenerator("k"));
    }

    @Override
    protected CatalogGateway labCatalog() {
        return client(merchant.baseUrl());
    }

    @Override
    protected CatalogGateway hostileCatalog() {
        try {
            StubMerchant stub = new StubMerchant();
            stubs.add(stub);
            String product = "{\"id\":\"item_666\",\"title\":\"Evil Mug\",\"description\":{\"plain\":\"" + HOSTILE_TEXT + "\"},"
                    + "\"variants\":[{\"id\":\"item_666\",\"title\":\"Evil Mug\",\"price\":{\"amount\":100,\"currency\":\"USD\"},"
                    + "\"availability\":{\"available\":true}}]}";
            String ucp = "{\"ucp\":{\"version\":\"2026-08-25\"},";
            stub.on("POST /ucp/v1/catalog/search", reply(200, ucp + "\"products\":[" + product + "]}"));
            stub.on("POST /ucp/v1/catalog/lookup", reply(200, ucp + "\"products\":[" + product + "]}"));
            stub.on("POST /ucp/v1/catalog/product", reply(200, ucp + "\"product\":" + product + "}"));
            return client(stub.baseUrl());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
