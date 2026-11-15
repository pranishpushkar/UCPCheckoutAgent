package dev.ucplab.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.orchestrator.DefaultOrchestrator;
import dev.ucplab.service.cli.Cli;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The command line client with two merchants (m6a C9; DESIGN 23.2, 23.3, FR3, FR5, S22): {@code intents} shows each
 * intent's merchant, {@code approval} names the merchant of the quote, one intent can be approved and the other left, and
 * {@code lab faults} and {@code lab orders} take {@code --merchant}.
 */
class CliMerchantsTest {

    private static final MerchantId A = new MerchantId("merchant-a");
    private static final MerchantId B = new MerchantId("merchant-b");

    @TempDir
    Path dir;

    private TestService service;
    private String baseUrl;
    private final List<String> secrets = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (service != null) {
            service.close();
        }
    }

    private void start() throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        baseUrl = "http://127.0.0.1:" + port;
        service = TestService.startMerchants(dir, Map.of("PUBLIC_ORIGIN", baseUrl), Optional.empty(), "active", true,
                Map.of(), port);
    }

    private record Result(int exit, String out, String err) {}

    private Result cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        List<String> all = new ArrayList<>(List.of("--user", "alice", "--base-url", baseUrl));
        all.addAll(List.of(args));
        int exit = Cli.run(all.toArray(String[]::new), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), secrets::add);
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private IntentId propose(MerchantId merchant, String item) {
        Clock clock = service.bean(Clock.class);
        AuthContext auth = new AuthContext(new UserId("local-alice"), "alice@local.ucplab.dev", clock.instant(), "s");
        return service.bean(DefaultOrchestrator.class)
                .propose(auth, merchant, List.of(new ProposedLine(item, 1)), "cli test").intentId();
    }

    private static String lineWith(String text, String id) {
        return text.lines().filter(l -> l.contains(id)).findFirst().orElse("");
    }

    // Covers: FR3, FR5, 23.2, S22 (intents shows two intents, each with its own merchant; approval names the merchant of the
    // quote; approving one completes it at its merchant and leaves the other awaiting, with no order at its merchant)
    @Test
    void twoIntents_areShownWithTheirMerchants_andOneCanBeApproved() throws Exception {
        start();
        IntentId atA = propose(A, "item_456");
        IntentId atB = propose(B, "b_mug_01");

        Result listed = cli("intents", "--state", "AWAITING_APPROVAL");

        assertThat(listed.exit()).isZero();
        assertThat(lineWith(listed.out(), atA.value())).contains("merchant-a");
        assertThat(lineWith(listed.out(), atB.value())).contains("merchant-b");
        Result card = cli("approval", atB.value());
        assertThat(card.out()).contains("merchant merchant-b").contains("b_mug_01").doesNotContain("merchant-a");

        Result approved = cli("approve", atB.value());
        assertThat(approved.exit()).isZero();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (service.merchantB.orders().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertThat(service.merchantB.orders()).hasSize(1);
        assertThat(service.merchant.orders()).isEmpty();
        Result left = cli("intents", "--state", "AWAITING_APPROVAL");
        assertThat(left.out()).contains(atA.value()).doesNotContain(atB.value());
        assertThat(secrets).isNotEmpty();
        assertThat(String.join(" ", cli("intents").out(), card.out(), approved.out())).doesNotContain(secrets.getFirst());
    }

    // Covers: 23.3, 24.3, m6a C9 (lab faults and lab orders take --merchant: a fault at B leaves A answering, orders are
    // counted at the merchant named, an unknown merchant is refused with the service's fixed message, and without
    // --merchant the first merchant is meant, as before)
    @Test
    void labCommands_takeAMerchant() throws Exception {
        start();
        IntentId atB = propose(B, "b_tee_02");
        cli("approve", atB.value());
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (service.merchantB.orders().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }

        assertThat(cli("lab", "orders", "--merchant", "merchant-b").out()).contains("orders 1");
        assertThat(cli("lab", "orders").out()).contains("orders 0");
        assertThat(cli("lab", "faults", "merchant_down", "--merchant", "merchant-b").out()).contains("fault set");
        assertThat(service.proxyB.log(0)).isNotNull();
        Result unknown = cli("lab", "faults", "merchant_down", "--merchant", "merchant-z");
        assertThat(unknown.exit()).isNotZero();
        assertThat(unknown.err()).contains("unknown merchant_id").doesNotContain("merchant-z");
        assertThat(cli("lab", "faults", "clear", "--merchant", "merchant-b").out()).contains("faults cleared");
        assertThat(cli("lab", "orders", "--merchant", "merchant-z").exit()).isNotZero();
    }
}
