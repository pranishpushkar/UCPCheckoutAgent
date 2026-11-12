package dev.ucplab.core.intent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.SessionId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PurchaseIntentMovedTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static PurchaseIntent intent(IntentState state) {
        return PurchaseIntent.proposed(new IntentId("i1"), new UserId("alice"), new MerchantId("shop-1"), "d1",
                List.of(new ProposedLine("item_123", 1)), "a@example.com", T0).withState(state, T0);
    }

    // Covers: 7.2, 7.6 (every row of the table: cancel_pending is set exactly when the row owes a cancel and a session exists)
    @Test
    void moved_setsCancelPending_exactlyForRowsThatOweACancel_withASession() {
        for (Transition t : TransitionTable.rows()) {
            PurchaseIntent withSession = intent(t.from()).withSessionId(new SessionId("s1"));
            PurchaseIntent withoutSession = intent(t.from());

            assertThat(withSession.moved(t, T0).cancelPending()).as("%s with a session", t).isEqualTo(t.owesCancel());
            assertThat(withoutSession.moved(t, T0).cancelPending()).as("%s without a session", t).isFalse();
            assertThat(withSession.moved(t, T0).state()).isEqualTo(t.to());
        }
    }

    // Covers: 7.6 (the rows that owe a cancel are the ones whose side effect cancels the session)
    @Test
    void owesCancel_isTrue_forCancelSideEffects_only() {
        long owing = TransitionTable.rows().stream().filter(Transition::owesCancel).count();

        assertThat(owing).isEqualTo(TransitionTable.rows().stream()
                .filter(t -> t.sideEffect() == SideEffect.CANCEL_SESSION
                        || t.sideEffect() == SideEffect.CANCEL_SESSION_BEST_EFFORT
                        || t.sideEffect() == SideEffect.REJECT_PRICE_UNSTABLE)
                .count()).isPositive();
    }
}
