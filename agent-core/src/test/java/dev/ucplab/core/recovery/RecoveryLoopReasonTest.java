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
import dev.ucplab.core.intent.LeaseLostException;
import dev.ucplab.core.intent.Orchestrator;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.intent.RecoveryScan;
import dev.ucplab.core.intent.StaleVersionException;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.storage.StorageException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A failed pass or step logs a fixed reason, never the exception's message (I8, 11; m6-plan section 12). The log is
 * captured from java.util.logging, where System.Logger writes with no LoggerFinder installed.
 */
class RecoveryLoopReasonTest {

    private static final String CANARY = "CANARY-RECOVERY-" + Long.toHexString(System.nanoTime());
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** A storage failure as agent-persistence's DatabaseException is one. */
    private static final class FakeDatabaseException extends StorageException {
        private static final long serialVersionUID = 1L;

        FakeDatabaseException(String message) {
            super(message);
        }
    }

    private final List<LogRecord> records = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private final ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    private final List<Logger> loggers = List.of(Logger.getLogger(RecoveryLoop.class.getName()),
            Logger.getLogger(RecoveryWorker.class.getName()));

    @BeforeEach
    void attach() {
        for (Logger l : loggers) {
            l.addHandler(capture);
            l.setLevel(Level.ALL);
        }
    }

    @AfterEach
    void detach() {
        for (Logger l : loggers) {
            l.removeHandler(capture);
            l.setLevel(null);
        }
        scheduler.shutdownNow();
    }

    /** Every captured line as it would be printed, with any attached throwable's message too. */
    private String printed() {
        SimpleFormatter f = new SimpleFormatter();
        StringBuilder out = new StringBuilder();
        for (LogRecord r : records) {
            out.append(f.formatMessage(r)).append('\n');
            assertThat(r.getThrown()).as("no exception is attached to the record").isNull();
        }
        return out.toString();
    }

    private static Orchestrator throwingAdvance(Supplier<RuntimeException> failure) {
        return new Orchestrator() {
            @Override
            public ProposalResult propose(AuthContext auth, MerchantId merchant, List<ProposedLine> lines, String why) {
                throw new UnsupportedOperationException();
            }

            @Override
            public StepResult advance(IntentId id) {
                throw failure.get();
            }

            @Override
            public IntentStatus status(AuthContext auth, IntentId id) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private RecoveryLoop loopWhoseScanThrows(RuntimeException failure) {
        MutableClock clock = new MutableClock(T0);
        InMemoryIntentRepository intents = new InMemoryIntentRepository(clock);
        RecoveryScan scan = (now, limit) -> {
            throw failure;
        };
        RecoveryWorker worker = new RecoveryWorker(intents, scan, throwingAdvance(IllegalStateException::new), clock,
                d -> { }, Runnable::run, RecoveryConfig.defaults(), "worker-reason");
        return new RecoveryLoop(worker, RecoveryConfig.defaults(), scheduler);
    }

    // Covers: I8, 11, m6-plan section 12 (each failure type maps to its fixed reason)
    @Test
    void reason_mapsEachFailureToAFixedCode() {
        IntentId id = new IntentId("i-1");
        assertThat(RecoveryLoop.reason(new LeaseLostException(id, CANARY))).isEqualTo("lease_lost");
        assertThat(RecoveryLoop.reason(new StaleVersionException(id, 1, 2))).isEqualTo("stale_version");
        assertThat(RecoveryLoop.reason(new FakeDatabaseException(CANARY))).isEqualTo("database");
        assertThat(RecoveryLoop.reason(new IllegalStateException(CANARY))).isEqualTo("internal");
        assertThat(RecoveryLoop.reason(new RuntimeException(CANARY))).isEqualTo("internal");
    }

    // Covers: I8, 11 (a pass that throws logs the fixed reason and never the message, which carries the canary)
    @Test
    void pass_thatThrows_logsTheReasonNotTheMessage() {
        for (RuntimeException failure : List.<RuntimeException>of(new FakeDatabaseException(CANARY),
                new IllegalStateException(CANARY), new LeaseLostException(new IntentId(CANARY), CANARY))) {
            records.clear();
            RecoveryLoop loop = loopWhoseScanThrows(failure);

            loop.pass();

            String printed = printed();
            assertThat(printed).contains("recovery pass failed: " + RecoveryLoop.reason(failure));
            assertThat(printed).doesNotContain(CANARY).doesNotContain(failure.getClass().getSimpleName());
            loop.close();
        }
    }

    // Covers: I8, 11 (a step that throws logs the fixed reason for the intent, never the message)
    @Test
    void driver_stepThatThrows_logsTheReasonNotTheMessage() {
        MutableClock clock = new MutableClock(T0);
        InMemoryIntentRepository intents = new InMemoryIntentRepository(clock);
        intents.insert(PurchaseIntent.proposed(new IntentId("q-1"), new UserId("alice"), new MerchantId("shop-1"), "d",
                List.of(new ProposedLine("item_123", 1)), "a@example.com", T0).withState(IntentState.QUOTING, T0));
        RecoveryWorker worker = new RecoveryWorker(intents, new InMemoryRecoveryScan(intents,
                new InMemoryApprovalRepository()), throwingAdvance(() -> new FakeDatabaseException(CANARY)), clock,
                d -> { }, Runnable::run, RecoveryConfig.defaults(), "worker-reason");

        assertThat(worker.runOnce()).containsExactly(new IntentId("q-1"));

        String printed = printed();
        assertThat(printed).contains("advance failed for intent q-1: database");
        assertThat(printed).doesNotContain(CANARY);
    }
}
