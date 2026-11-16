package dev.ucplab.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentRepository;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.orchestrator.DefaultOrchestrator;
import dev.ucplab.faultproxy.LogEntry;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * I12 through the real service (26.5, 7.2, H5 C2 ★): a merchant whose entry says {@code owned: false} is never sent a
 * create. It is a lab merchant on loopback, which the file allows to be unowned, so the stack starts; the guard, not
 * startup, must stop the write.
 */
class OwnStoreGuardServiceTest {

    private static final MerchantId A = new MerchantId("merchant-a");
    private static final String SECRET = "s3cr3t-value-0123456789abcdefghijklmnop";

    @TempDir
    Path dir;

    private TestService service;

    @AfterEach
    void stop() {
        if (service != null) {
            service.close();
        }
    }

    // Covers: I12, 26.5, 7.2 (PROPOSED policy_denied with rule id I12), H5 C2 (nothing reaches an unowned merchant:
    // no request at its proxy, no attempt row, the intent REJECTED with rule I12)
    @Test
    void propose_atAnUnownedMerchant_sendsNothing_andIsDeniedWithI12() throws IOException {
        service = TestService.startMerchantsWithA(dir, Map.of("APPROVAL_SECRET", SECRET), Optional.empty(),
                "    owned: false\n");
        long before = service.proxy.log(0).stream().mapToLong(LogEntry::seq).max().orElse(0);
        AuthContext alice = new AuthContext(new UserId("local-alice"), "alice@local.ucplab.dev",
                Clock.systemUTC().instant(), "s");

        ProposalResult result = service.bean(DefaultOrchestrator.class).propose(alice, A,
                List.of(new ProposedLine("item_123", 1)), "own-store guard");

        List<LogEntry> sent = service.proxy.log(before).stream()
                .filter(e -> !"GET".equals(e.method())).toList();
        assertThat(sent).as("mutating requests at the unowned merchant").isEmpty();
        assertThat(result.state()).isEqualTo(IntentState.REJECTED);
        IntentRepository intents = service.bean(IntentRepository.class);
        assertThat(intents.attempts(result.intentId())).isEmpty();
        assertThat(intents.findForSystem(result.intentId()).orElseThrow().failureReason()).contains("policy_I12");
    }

    // Covers: I12, 19, 10 (an intent awaiting approval at merchant-a stores its entry's fingerprint; a restart whose file
    // marks merchant-a owned: false while that intent is in flight refuses startup with the fixed reason. A restart with
    // the entry unchanged and an intent in flight: MultiMerchantServiceTest.anInFlightIntentAtASuspendedMerchant_...)
    @Test
    void restart_withAChangedEntryWhileAnIntentIsInFlight_refusesStartup() throws IOException {
        service = TestService.startMerchants(dir, Map.of("APPROVAL_SECRET", SECRET), Optional.empty(), "active", true);
        AuthContext alice = new AuthContext(new UserId("local-alice"), "alice@local.ucplab.dev",
                Clock.systemUTC().instant(), "s");
        ProposalResult result = service.bean(DefaultOrchestrator.class).propose(alice, A,
                List.of(new ProposedLine("item_123", 1)), "in flight");
        assertThat(result.state()).isEqualTo(IntentState.AWAITING_APPROVAL);
        assertThat(service.bean(IntentRepository.class).findForSystem(result.intentId()).orElseThrow()
                .merchantFingerprint()).isEqualTo(MerchantEntry.LAB_FINGERPRINT);
        TestService first = service;
        service = null;

        try {
            assertThatThrownBy(() -> TestService.restartMerchantsWithA(first, dir, Map.of("APPROVAL_SECRET", SECRET),
                    Optional.empty(), "    owned: false\n").close())
                    .hasStackTraceContaining("MERCHANTS_FILE changes the owned, shop_domain, transport or completion of a"
                            + " merchant with intents in flight");
        } finally {
            first.close();
        }
    }
}
