package dev.ucplab.core.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class TransitionTableTest {

    /** Table 7.2 copied row by row from the design, independently of the production table. */
    private static final List<Arguments> EXPECTED = List.of(
            row(1, "PROPOSED", "POLICY_OK", "POLICY_ALLOWS", "QUOTING", "NONE"),
            row(2, "PROPOSED", "POLICY_DENIED", "NONE", "REJECTED", "RECORD_POLICY_RULE"),
            row(3, "QUOTING", "QUOTE_READY", "QUOTE_POLICY_ALLOWS", "AWAITING_APPROVAL", "CREATE_APPROVAL"),
            row(4, "QUOTING", "ITEM_UNAVAILABLE", "NONE", "FAILED_NOT_CHARGED", "CANCEL_SESSION_BEST_EFFORT"),
            row(4, "QUOTING", "OUT_OF_STOCK", "NONE", "FAILED_NOT_CHARGED", "CANCEL_SESSION_BEST_EFFORT"),
            row(5, "QUOTING", "NOT_SENT", "RETRIES_LEFT", "QUOTING", "RESEND_SAME_KEY"),
            row(5, "QUOTING", "UNCERTAIN", "RETRIES_LEFT", "QUOTING", "RESEND_SAME_KEY"),
            row(6, "QUOTING", "RETRIES_EXHAUSTED", "NONE", "FAILED_NOT_CHARGED", "NONE"),
            row(7, "AWAITING_APPROVAL", "APPROVED", "APPROVAL_VALID", "APPROVED", "CONSUME_APPROVAL"),
            row(8, "AWAITING_APPROVAL", "REJECTED", "NONE", "REJECTED", "CANCEL_SESSION"),
            row(8, "AWAITING_APPROVAL", "EXPIRED", "NONE", "REJECTED", "CANCEL_SESSION"),
            row(9, "APPROVED", "SEND_COMPLETE", "ATTEMPT_PERSISTED", "COMPLETING", "POST_COMPLETE"),
            row(10, "COMPLETING", "MERCHANT_COMPLETED", "NONE", "COMPLETED", "STORE_ORDER_ID"),
            row(11, "COMPLETING", "PAYMENT_DECLINED", "NONE", "FAILED_NOT_CHARGED", "CANCEL_SESSION"),
            row(12, "COMPLETING", "PRICE_CHANGED", "REQUOTE_BELOW_CAP", "QUOTING", "REQUOTE_ON_SAME_SESSION"),
            row(12, "COMPLETING", "CHECKOUT_INCOMPLETE", "REQUOTE_BELOW_CAP", "QUOTING", "REQUOTE_ON_SAME_SESSION"),
            row(13, "COMPLETING", "UNCERTAIN", "NONE", "RECONCILING", "NONE"),
            row(14, "RECONCILING", "OBSERVED_COMPLETED", "NONE", "COMPLETED", "STORE_ORDER_ID"),
            row(15, "RECONCILING", "OBSERVED_COMPLETE_IN_PROGRESS", "NONE", "RECONCILING", "POLL_AGAIN"),
            row(16, "RECONCILING", "OBSERVED_READY_FOR_COMPLETE", "RESEND_ALLOWED", "COMPLETING", "RESEND_COMPLETE"),
            row(17, "RECONCILING", "OBSERVED_CANCELED", "NONE", "FAILED_NOT_CHARGED", "NONE"),
            row(18, "RECONCILING", "OBSERVED_INCOMPLETE", "REQUOTE_BELOW_CAP", "QUOTING", "REQUOTE_ON_SAME_SESSION"),
            row(19, "RECONCILING", "DEADLINE_REACHED", "NONE", "ESCALATED", "ESCALATION_EVENT"),
            row(20, "COMPLETING", "PRICE_CHANGED", "REQUOTE_CAP_REACHED", "REJECTED", "REJECT_PRICE_UNSTABLE"),
            row(20, "COMPLETING", "CHECKOUT_INCOMPLETE", "REQUOTE_CAP_REACHED", "REJECTED", "REJECT_PRICE_UNSTABLE"),
            row(20, "RECONCILING", "OBSERVED_INCOMPLETE", "REQUOTE_CAP_REACHED", "REJECTED", "REJECT_PRICE_UNSTABLE"),
            row(21, "RECONCILING", "OBSERVED_READY_FOR_COMPLETE", "QUIET_PERIOD_NOT_ELAPSED", "RECONCILING", "POLL_AGAIN"),
            row(22, "QUOTING", "OBSERVED_COMPLETED", "NONE", "RECONCILING", "NONE"),
            row(22, "QUOTING", "OBSERVED_COMPLETE_IN_PROGRESS", "NONE", "RECONCILING", "NONE"),
            row(23, "QUOTING", "CREATE_REJECTED", "NONE", "FAILED_NOT_CHARGED", "NONE"),
            row(24, "COMPLETING", "PROTOCOL_ERROR", "NONE", "RECONCILING", "NONE"));

    static Stream<Arguments> expectedRows() {
        return EXPECTED.stream();
    }

    static Stream<Arguments> guardedRows() {
        return EXPECTED.stream().filter(a -> a.get()[3] != TransitionGuard.NONE);
    }

    static Stream<Arguments> illegalPairs() {
        List<Arguments> pairs = new ArrayList<>();
        for (IntentState from : IntentState.values()) {
            for (TransitionEvent event : TransitionEvent.values()) {
                boolean listed = EXPECTED.stream().anyMatch(a -> a.get()[1] == from && a.get()[2] == event);
                if (!listed) {
                    pairs.add(Arguments.of(from, event));
                }
            }
        }
        return pairs.stream();
    }

    // Covers: 7.2 (every row), I3 (row 7 needs APPROVAL_VALID; row 9 is the only way into COMPLETING from APPROVED)
    @ParameterizedTest(name = "row {0}: {1} --{2}[{3}]--> {4}")
    @MethodSource("expectedRows")
    void transitionTable_resolvesToRowTarget_whenRowGuardHolds(
            int docRow, IntentState from, TransitionEvent event, TransitionGuard guard, IntentState to, SideEffect effect) {
        Transition t = TransitionTable.resolve(from, event, EnumSet.of(guard));

        assertThat(t).isEqualTo(new Transition(from, event, guard, to, effect));
    }

    // Covers: 7.2 (guards are enforced, not advisory), I3
    @ParameterizedTest(name = "row {0}: {1} --{2}--> refused without {3}")
    @MethodSource("guardedRows")
    void transitionTable_throws_whenRowGuardDoesNotHold(
            int docRow, IntentState from, TransitionEvent event, TransitionGuard guard, IntentState to, SideEffect effect) {
        assertThatThrownBy(() -> TransitionTable.resolve(from, event, Set.of()))
                .isInstanceOf(IllegalTransitionException.class);
    }

    // Covers: 7.2 ("any event that is not in this table for the current state ... throw IllegalTransitionException"), 7.1 (terminal states)
    @ParameterizedTest(name = "{0} --{1}--> illegal")
    @MethodSource("illegalPairs")
    void transitionTable_throws_whenEventNotListedForState(IntentState from, TransitionEvent event) {
        // Every guard holds, so only the absence of a row can cause the refusal.
        assertThatThrownBy(() -> TransitionTable.resolve(from, event, EnumSet.allOf(TransitionGuard.class)))
                .isInstanceOf(IllegalTransitionException.class)
                .satisfies(e -> {
                    IllegalTransitionException ite = (IllegalTransitionException) e;
                    assertThat(ite.from()).isEqualTo(from);
                    assertThat(ite.event()).isEqualTo(event);
                });
    }

    // Covers: 7.1 (terminal states accept no event)
    @ParameterizedTest
    @EnumSource(value = IntentState.class, names = {"COMPLETED", "REJECTED", "FAILED_NOT_CHARGED", "ESCALATED"})
    void transitionTable_hasNoRowFromState_whenStateIsTerminal(IntentState terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        assertThat(TransitionTable.rows()).noneMatch(t -> t.from() == terminal);
    }

    // Covers: 7.1
    @ParameterizedTest
    @EnumSource(value = IntentState.class, names = {"PROPOSED", "QUOTING", "AWAITING_APPROVAL", "APPROVED", "COMPLETING", "RECONCILING"})
    void intentState_isNotTerminal_whenStateIsNonTerminal(IntentState state) {
        assertThat(state.isTerminal()).isFalse();
    }

    // Covers: 7.2 (rows 12 and 20, 18 and 20, 16 and 21 are mutually exclusive)
    @Test
    void transitionTable_throws_whenCompetingGuardsBothHold() {
        assertThatThrownBy(() -> TransitionTable.resolve(IntentState.COMPLETING, TransitionEvent.PRICE_CHANGED,
                EnumSet.of(TransitionGuard.REQUOTE_BELOW_CAP, TransitionGuard.REQUOTE_CAP_REACHED)))
                .isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> TransitionTable.resolve(IntentState.RECONCILING, TransitionEvent.OBSERVED_INCOMPLETE,
                EnumSet.of(TransitionGuard.REQUOTE_BELOW_CAP, TransitionGuard.REQUOTE_CAP_REACHED)))
                .isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> TransitionTable.resolve(IntentState.RECONCILING, TransitionEvent.OBSERVED_READY_FOR_COMPLETE,
                EnumSet.of(TransitionGuard.RESEND_ALLOWED, TransitionGuard.QUIET_PERIOD_NOT_ELAPSED)))
                .isInstanceOf(IllegalTransitionException.class);
    }

    // Covers: 7.2 (the production table has exactly the documented entries, no extras)
    @Test
    void transitionTable_containsExactlyDocumentedRows_always() {
        List<Transition> expected = EXPECTED.stream()
                .map(a -> new Transition((IntentState) a.get()[1], (TransitionEvent) a.get()[2],
                        (TransitionGuard) a.get()[3], (IntentState) a.get()[4], (SideEffect) a.get()[5]))
                .toList();

        assertThat(TransitionTable.rows()).containsExactlyInAnyOrderElementsOf(expected);
    }

    // Covers: I3 (COMPLETING is entered only from APPROVED with APPROVAL_VALID upstream, or from RECONCILING by a resend)
    @Test
    void transitionTable_entersCompletingOnlyFromApprovedOrReconcilingResend_always() {
        assertThat(TransitionTable.rows())
                .filteredOn(t -> t.to() == IntentState.COMPLETING)
                .extracting(Transition::from, Transition::guard)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(IntentState.APPROVED, TransitionGuard.ATTEMPT_PERSISTED),
                        org.assertj.core.groups.Tuple.tuple(IntentState.RECONCILING, TransitionGuard.RESEND_ALLOWED));
        assertThat(TransitionTable.rows())
                .filteredOn(t -> t.to() == IntentState.APPROVED)
                .extracting(Transition::from, Transition::guard)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(IntentState.AWAITING_APPROVAL, TransitionGuard.APPROVAL_VALID));
    }

    private static Arguments row(int docRow, String from, String event, String guard, String to, String effect) {
        return Arguments.of(docRow, IntentState.valueOf(from), TransitionEvent.valueOf(event),
                TransitionGuard.valueOf(guard), IntentState.valueOf(to), SideEffect.valueOf(effect));
    }
}
