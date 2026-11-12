package dev.ucplab.core.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.approval.InMemoryApprovalRepository;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.InMemoryIntentRepository;
import dev.ucplab.core.intent.InMemoryRecoveryScan;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.IntentStatus;
import dev.ucplab.core.intent.Orchestrator;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.core.merchant.MerchantId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** RecoveryWorker's parked intents (7.6 "Long waits"): an entry is forgotten once the intent no longer needs a worker. */
class RecoveryWorkerParkingTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final String WORKER = "worker-p";

    private final MutableClock clock = new MutableClock(T0);
    private final InMemoryIntentRepository intents = new InMemoryIntentRepository(clock);
    private final InMemoryRecoveryScan scan = new InMemoryRecoveryScan(intents, new InMemoryApprovalRepository());

    /** Every advance asks to be called again in 20 s: longer than half the 30 s lease, so the worker parks the intent. */
    private static final class LongWait implements Orchestrator {
        @Override
        public ProposalResult propose(AuthContext auth, MerchantId merchant, List<ProposedLine> lines, String why) {
            throw new UnsupportedOperationException();
        }

        @Override
        public StepResult advance(IntentId id) {
            return new StepResult(id, IntentState.RECONCILING, IntentState.RECONCILING, false,
                    Optional.of(T0.plusSeconds(20)));
        }

        @Override
        public IntentStatus status(AuthContext auth, IntentId id) {
            throw new UnsupportedOperationException();
        }
    }

    // Covers: 7.6 (long waits), I5 (a parked intent that went terminal is forgotten once it is due, instead of staying
    // in the worker's parking map for the life of the process)
    @Test
    void parkedEntry_isForgotten_whenTheIntentIsNoLongerRecoverableAtItsDueTime() {
        PurchaseIntent intent = PurchaseIntent.proposed(new IntentId("p-1"), new UserId("alice"),
                new MerchantId("shop-1"), "dedupe-p", List.of(new ProposedLine("item_123", 1)), "alice@example.com", T0)
                .withState(IntentState.RECONCILING, T0);
        intents.insert(intent);
        RecoveryWorker worker = new RecoveryWorker(intents, scan, new LongWait(), clock, d -> { }, Runnable::run,
                RecoveryConfig.defaults(), WORKER);

        worker.runOnce();
        assertThat(worker.parkedCount()).isEqualTo(1);

        PurchaseIntent stored = intents.findForSystem(intent.id()).orElseThrow();
        intents.update(stored.withState(IntentState.COMPLETED, clock.instant()), stored.version(), WORKER);
        clock.advance(Duration.ofSeconds(21));

        assertThat(worker.runOnce()).isEmpty();
        assertThat(worker.parkedCount()).isZero();
    }
}
