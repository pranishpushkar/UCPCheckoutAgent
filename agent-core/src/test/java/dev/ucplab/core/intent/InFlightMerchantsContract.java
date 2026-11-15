package dev.ucplab.core.intent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.merchant.MerchantId;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The merchants of intents still in flight (DESIGN 19, 18.1): a system read beside {@link RecoveryScan}, across owners,
 * run against the in-memory and the SQL adapter. It exists so startup can refuse a file that drops a merchant whose
 * intents could then never reconcile.
 */
public abstract class InFlightMerchantsContract {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final MerchantId A = new MerchantId("merchant-a");
    private static final MerchantId B = new MerchantId("merchant-b");
    private static final MerchantId C = new MerchantId("merchant-c");

    protected final MutableClock clock = new MutableClock(T0);
    protected IntentRepository intents;
    protected InFlightMerchants inFlight;

    /** Sets {@link #intents} and {@link #inFlight}: fresh, empty and sharing one store. */
    protected abstract void create(MutableClock clock);

    @BeforeEach
    void createStore() {
        create(clock);
    }

    private void insert(String id, String user, MerchantId merchant, IntentState state) {
        PurchaseIntent intent = PurchaseIntent.proposed(new IntentId(id), new UserId(user), merchant, "d-" + id,
                List.of(new ProposedLine("item_123", 1)), "a@example.com", clock.instant())
                .withState(state, clock.instant());
        // The SQL schema requires an order for COMPLETED (section 10, V4).
        intents.insert(state == IntentState.COMPLETED ? intent.withOrderId("order-" + id) : intent);
    }

    // Covers: 19, 18.1 (with no intent there is no merchant in flight)
    @Test
    void merchantsInFlight_noIntents_isEmpty() {
        assertThat(inFlight.merchantsInFlight()).isEmpty();
    }

    // Covers: 19, 18.1 (every non-terminal state counts, whoever owns the intent: this read crosses owners like RecoveryScan)
    @Test
    void merchantsInFlight_everyNonTerminalState_acrossOwners() {
        int n = 0;
        for (IntentState state : IntentState.values()) {
            if (!state.isTerminal()) {
                insert("i" + n, n % 2 == 0 ? "alice" : "bob", n % 2 == 0 ? A : B, state);
                n++;
            }
        }

        assertThat(inFlight.merchantsInFlight()).isEqualTo(Set.of(A, B));
    }

    // Covers: 19 (a merchant whose intents are all terminal is not in flight, and each merchant is listed once)
    @Test
    void merchantsInFlight_terminalIntentsDoNotCount_andMerchantsAreDistinct() {
        insert("i1", "alice", A, IntentState.AWAITING_APPROVAL);
        insert("i2", "bob", A, IntentState.COMPLETING);
        insert("i3", "alice", B, IntentState.COMPLETED);
        insert("i4", "alice", C, IntentState.REJECTED);
        insert("i5", "alice", C, IntentState.FAILED_NOT_CHARGED);
        insert("i6", "alice", C, IntentState.ESCALATED);

        assertThat(inFlight.merchantsInFlight()).isEqualTo(Set.of(A));
    }
}
