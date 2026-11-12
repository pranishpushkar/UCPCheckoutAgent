package dev.ucplab.eval.check;

import static dev.ucplab.eval.check.CheckFixture.COMPLETE_BODY;
import static dev.ucplab.eval.check.CheckFixture.CONTEXT;
import static dev.ucplab.eval.check.CheckFixture.CREATE_BODY;
import static dev.ucplab.eval.check.CheckFixture.ORDER;
import static dev.ucplab.eval.check.CheckFixture.SESSION;
import static dev.ucplab.eval.check.CheckFixture.at;
import static dev.ucplab.eval.check.CheckFixture.attempt;
import static dev.ucplab.eval.check.CheckFixture.complete;
import static dev.ucplab.eval.check.CheckFixture.decision;
import static dev.ucplab.eval.check.CheckFixture.entry;
import static dev.ucplab.eval.check.CheckFixture.key;
import static dev.ucplab.eval.check.CheckFixture.order;
import static dev.ucplab.eval.check.CheckFixture.row;
import static dev.ucplab.eval.check.CheckFixture.session;
import static dev.ucplab.eval.check.CheckFixture.snapshot;
import static dev.ucplab.eval.check.CheckFixture.transition;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.approval.ApprovalSnapshot;
import dev.ucplab.core.approval.ApprovalStatus;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.eval.AutoApprover;
import dev.ucplab.faultproxy.LogEntry;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * The checks of m4-plan section 5 on hand-made evidence: the clean fixture passes every check, and each failure mode
 * makes the check it belongs to fire with a detail that names it. Every test breaks exactly one thing.
 */
class ChecksTest {

    private static List<String> fire(String invariant, CheckFixture f) {
        return Checks.byId(invariant).orElseThrow().check(f.build(), CONTEXT).stream().map(Violation::detail)
                .toList();
    }

    private static void assertFires(String invariant, CheckFixture f, String detail) {
        assertThat(fire(invariant, f)).as(invariant).anySatisfy(d -> assertThat(d).contains(detail));
    }

    // Covers: I1, I2, I3, I4, I5, I7 (the clean fixture is clean, so every other test's violation is its own)
    @Test
    void checks_cleanFixture_findNothing() {
        CheckResult result = Checks.run(new CheckFixture().build(), CONTEXT);

        assertThat(result.all()).isEmpty();
        assertThat(result.byInvariant()).containsOnlyKeys("I1", "I2", "I3", "I4", "I5", "I7");
    }

    // Covers: I1, I2, I3, I4, I5, I7 (an aborted run fails every check: none could be established)
    @Test
    void checks_abortedRun_failEveryCheck() {
        CheckFixture f = new CheckFixture();
        f.aborted = "no terminal state within the time limit PT20S";

        CheckResult result = Checks.run(f.build(), CONTEXT);

        assertThat(result.byInvariant()).allSatisfy((id, v) -> assertThat(v).singleElement()
                .satisfies(x -> assertThat(x.detail()).contains("aborted", "PT20S")));
    }

    // ------------------------------------------------------------------ I1

    // Covers: I1 (two orders in one run)
    @Test
    void i1_twoOrders_fires() {
        CheckFixture f = new CheckFixture();
        f.orders.add(order("ord_2", SESSION, 5000));

        assertFires("I1", f, "holds 2 orders");
    }

    // Covers: I1 (an order whose session the merchant does not show completed with it)
    @Test
    void i1_orderOnSessionNotCompletedWithIt_fires() {
        CheckFixture f = new CheckFixture();
        f.sessions.put(SESSION, session(SESSION, "completed", "USD", 2, Optional.of("ord_9")));

        assertFires("I1", f, "with order ord_9");
    }

    // Covers: I1 (an order whose session the merchant does not have)
    @Test
    void i1_orderWithoutSession_fires() {
        CheckFixture f = new CheckFixture();
        f.sessions.clear();

        assertFires("I1", f, "has no session cs_1");
    }

    // ------------------------------------------------------------------ I2

    // Covers: I2 (an amount above the approved maximum)
    @Test
    void i2_amountAboveMaximum_fires() {
        CheckFixture f = new CheckFixture();
        f.orders.set(0, order(ORDER, SESSION, 5001));

        assertFires("I2", f, "amount 5001 is above the approved maximum 5000");
    }

    // Covers: I2 (the merchant's lines differ from the approved lines)
    @Test
    void i2_quantityDiffersFromApproval_fires() {
        CheckFixture f = new CheckFixture();
        f.sessions.put(SESSION, session(SESSION, "completed", "USD", 3, Optional.of(ORDER)));

        assertFires("I2", f, "the merchant holds [item_123x3]");
    }

    // Covers: I2 (the merchant's currency differs from the approved currency)
    @Test
    void i2_currencyDiffersFromApproval_fires() {
        CheckFixture f = new CheckFixture();
        f.sessions.put(SESSION, session(SESSION, "completed", "EUR", 2, Optional.of(ORDER)));

        assertFires("I2", f, "currency is EUR");
    }

    // Covers: I2 (the approval in force names another merchant)
    @Test
    void i2_approvalForAnotherMerchant_fires() {
        CheckFixture f = new CheckFixture();
        ApprovalSnapshot other = new ApprovalSnapshot(CheckFixture.INTENT,
                new dev.ucplab.core.merchant.MerchantId("shop-other"), f.snapshot.sessionId(), f.snapshot.lines(),
                "USD", 5000, 5000, f.snapshot.expiresAt());
        f.approvals.set(0, decision(1, other, true, 200));

        assertFires("I2", f, "names merchant shop-other");
    }

    // Covers: I2 (the approval in force is for another session)
    @Test
    void i2_approvalForAnotherSession_fires() {
        CheckFixture f = new CheckFixture();
        f.approvals.set(0, decision(1, snapshot("cs_9", 5000, 5000), true, 200));

        assertFires("I2", f, "is for session cs_9");
    }

    // Covers: I2 (no approval was logged before the order's first complete)
    @Test
    void i2_noApprovalBeforeTheCompleteThatMadeTheOrder_fires() {
        CheckFixture f = new CheckFixture();
        f.approvals.set(0, decision(1, f.snapshot, true, 300));

        assertFires("I2", f, "the user approved nothing before");
    }

    // Covers: I2 (an order no complete on the wire could have made)
    @Test
    void i2_orderWithNoCompleteOnTheWire_fires() {
        CheckFixture f = new CheckFixture();
        f.log.removeIf(Wire::isComplete);

        assertFires("I2", f, "no complete for that session reached the merchant");
    }

    // ------------------------------------------------------------------ I3

    // Covers: I3 (a complete that arrived before its approval)
    @Test
    void i3_completeBeforeItsApproval_fires() {
        CheckFixture f = new CheckFixture();
        f.approvals.set(0, decision(1, f.snapshot, true, 300));

        assertFires("I3", f, "arrived before its approval");
    }

    // Covers: I3 (a complete with no decision at all)
    @Test
    void i3_completeWithoutAnyDecision_fires() {
        CheckFixture f = new CheckFixture();
        f.approvals.clear();

        assertFires("I3", f, "was sent without decision #1");
    }

    // Covers: I3 (a complete under the revision of a declined decision)
    @Test
    void i3_completeAfterTheUserDeclined_fires() {
        CheckFixture f = new CheckFixture();
        f.approvals.set(0, decision(1, f.snapshot, false, 200));

        assertFires("I3", f, "the user declined decision #1");
    }

    // Covers: I3 (no complete is forwarded after a decline: a later decline does not let the old key through)
    @Test
    void i3_completeAfterALaterDecline_fires() {
        CheckFixture f = new CheckFixture();
        f.approvals.add(decision(2, snapshot(SESSION, 5200, 5200), false, 240));

        assertFires("I3", f, "after the user declined decision #2");
    }

    // Covers: I3 (approved after the snapshot expired)
    @Test
    void i3_approvedAfterExpiry_fires() {
        CheckFixture f = new CheckFixture();
        ApprovalSnapshot expired = new ApprovalSnapshot(CheckFixture.INTENT, f.snapshot.merchantId(),
                f.snapshot.sessionId(), f.snapshot.lines(), "USD", 5000, 5000, at(150));
        f.approvals.set(0, decision(1, expired, true, 200));
        f.approvalRows.set(0, row("ap-1", expired, ApprovalStatus.CONSUMED, Optional.of(CheckFixture.OWNER)));

        assertFires("I3", f, "after the snapshot expired");
    }

    // Covers: I3 (the approved hash does not match its snapshot)
    @Test
    void i3_hashDoesNotMatchTheSnapshot_fires() {
        CheckFixture f = new CheckFixture();
        f.approvals.set(0, new AutoApprover.Decision(CheckFixture.INTENT, 1, f.snapshot, "0".repeat(64), true,
                at(200)));

        assertFires("I3", f, "does not match its snapshot");
    }

    // Covers: I3 (the complete targets a session the approval did not name)
    @Test
    void i3_completeToAnotherSession_fires() {
        CheckFixture f = new CheckFixture();
        f.log.set(2, complete(3, 260, "cs_2", 0, COMPLETE_BODY, 200));

        assertFires("I3", f, "targets cs_2, but decision #1 approved cs_1");
    }

    // Covers: I3 (a complete under a key that is no intent's complete key)
    @Test
    void i3_completeUnderAForeignKey_fires() {
        CheckFixture f = new CheckFixture();
        f.log.set(2, entry(3, 260, "POST", "/ucp/v1/checkout-sessions/cs_1/complete", Optional.of("not-ours"),
                Optional.of(COMPLETE_BODY), 200));

        assertFires("I3", f, "which is no intent's complete key");
    }

    // Covers: I3 (a complete under the create key is not a complete key either)
    @Test
    void i3_completeUnderTheCreateKey_fires() {
        CheckFixture f = new CheckFixture();
        f.log.set(2, entry(3, 260, "POST", "/ucp/v1/checkout-sessions/cs_1/complete",
                Optional.of(key(MutatingOp.CREATE, 0)), Optional.of(COMPLETE_BODY), 200));

        assertFires("I3", f, "which is no intent's complete key");
    }

    // Covers: I3 (the stored approval row is not consumed)
    @Test
    void i3_storedRowStillPending_fires() {
        CheckFixture f = new CheckFixture();
        f.approvalRows.set(0, row("ap-1", f.snapshot, ApprovalStatus.PENDING, Optional.of(CheckFixture.OWNER)));

        assertFires("I3", f, "row ap-1 for decision #1 is PENDING");
    }

    // Covers: I3, I10 (the stored approval row was decided by someone other than the run's user)
    @Test
    void i3_storedRowDecidedByAnotherUser_fires() {
        CheckFixture f = new CheckFixture();
        f.approvalRows.set(0, row("ap-1", f.snapshot, ApprovalStatus.CONSUMED, Optional.of(new UserId("mallory"))));

        assertFires("I3", f, "decided by mallory");
    }

    // Covers: I3 (D holds no row for the decision)
    @Test
    void i3_noStoredRow_fires() {
        CheckFixture f = new CheckFixture();
        f.approvalRows.clear();

        assertFires("I3", f, "D holds no approval row for decision #1");
    }

    // ------------------------------------------------------------------ I4

    // Covers: I4 (a second create under the intent's key after its first complete)
    @Test
    void i4_createAfterTheFirstComplete_fires() {
        CheckFixture f = new CheckFixture();
        f.log.add(entry(4, 300, "POST", "/ucp/v1/checkout-sessions", Optional.of(key(MutatingOp.CREATE, 0)),
                Optional.of(CREATE_BODY), 201));

        assertFires("I4", f, "is a create under the intent's key after the first complete");
    }

    // Covers: I4 (a create with no key after the first complete, in a run of one intent)
    @Test
    void i4_unkeyedCreateAfterTheFirstComplete_fires() {
        CheckFixture f = new CheckFixture();
        f.log.add(entry(4, 300, "POST", "/ucp/v1/checkout-sessions", Optional.empty(), Optional.of(CREATE_BODY),
                201));

        assertFires("I4", f, "is a create after the first complete");
    }

    // Covers: I4 (a request to another session after the first complete)
    @Test
    void i4_requestToAnotherSessionAfterTheFirstComplete_fires() {
        CheckFixture f = new CheckFixture();
        f.log.add(entry(4, 300, "GET", "/ucp/v1/checkout-sessions/cs_2", Optional.empty(), Optional.empty(), 200));

        assertFires("I4", f, "targets cs_2 after the first complete");
    }

    // Covers: I4 (the stored session id changed after the first complete)
    @Test
    void i4_storedSessionDiffers_fires() {
        CheckFixture f = new CheckFixture();
        f.storedSession = Optional.of("cs_2");

        assertFires("I4", f, "finally stored session cs_2");
    }

    // Covers: I4 (requests to the same session after the first complete are fine)
    @Test
    void i4_getOfTheSameSessionAfterTheFirstComplete_isClean() {
        CheckFixture f = new CheckFixture();
        f.log.add(entry(4, 300, "GET", "/ucp/v1/checkout-sessions/cs_1", Optional.empty(), Optional.empty(), 200));

        assertThat(fire("I4", f)).isEmpty();
    }

    // ------------------------------------------------------------------ I5

    // Covers: I5 (an intent that ended in a non-terminal state)
    @Test
    void i5_notTerminal_fires() {
        CheckFixture f = new CheckFixture();
        f.state = IntentState.RECONCILING;

        assertFires("I5", f, "ended RECONCILING");
    }

    // Covers: I5, 7.6 (a terminal intent still owing a cancel)
    @Test
    void i5_cancelStillOwed_fires() {
        CheckFixture f = new CheckFixture();
        f.state = IntentState.FAILED_NOT_CHARGED;
        f.cancelPending = true;

        assertFires("I5", f, "still owing a cancel");
    }

    // Covers: I5 (the harness never saw the run terminal)
    @Test
    void i5_terminalNeverSeen_fires() {
        CheckFixture f = new CheckFixture();
        f.terminalSeenAt = Optional.empty();

        assertFires("I5", f, "never saw every intent terminal");
    }

    // Covers: I5 (propose returned an intent D does not hold)
    @Test
    void i5_proposedIntentMissingFromD_fires() {
        CheckFixture f = new CheckFixture();
        f.proposed.add(new IntentId("it-ghost"));

        assertFires("I5", f, "D holds no such intent");
    }

    // Covers: I5, 7.6 (QUOTING outlasted one worker period plus one call of 4 attempts and backoff)
    @Test
    void i5_quotingOutlastedItsBound_fires() {
        CheckFixture f = new CheckFixture();
        f.events.set(1, transition("QUOTING", "AWAITING_APPROVAL", 30_000));

        assertFires("I5", f, "in QUOTING");
    }

    // Covers: I5, 7.6 (APPROVED outlasted one worker period plus the complete timeout)
    @Test
    void i5_approvedOutlastedItsBound_fires() {
        CheckFixture f = new CheckFixture();
        f.events.set(3, transition("APPROVED", "COMPLETING", 5_000));

        assertFires("I5", f, "in APPROVED");
    }

    // Covers: I5, 7.5, 7.6 (COMPLETING and RECONCILING together outlasted the complete timeout plus the deadline)
    @Test
    void i5_reconcilingOutlastedTheDeadline_fires() {
        CheckFixture f = new CheckFixture();
        f.events.set(4, transition("COMPLETING", "RECONCILING", 2_800));
        f.events.add(transition("RECONCILING", "ESCALATED", 30_000));
        f.state = IntentState.ESCALATED;

        assertFires("I5", f, "in COMPLETING/RECONCILING");
    }

    // Covers: I5 (from the last key's first complete in L to the harness seeing the end took too long)
    @Test
    void i5_terminalSeenTooLongAfterTheLastComplete_fires() {
        CheckFixture f = new CheckFixture();
        f.terminalSeenAt = Optional.of(at(60_000));

        assertFires("I5", f, "was first seen terminal");
    }

    // Covers: I5, 7.6 (a crash extends every bound by one lease plus one worker period)
    @Test
    void i5_crashExtendsTheBound() {
        CheckFixture f = new CheckFixture();
        f.events.set(3, transition("APPROVED", "COMPLETING", 5_000));
        f.events.set(4, transition("COMPLETING", "COMPLETED", 5_100));

        assertThat(new I5Liveness().check(f.build(), CONTEXT.withCrashes(1))).isEmpty();
    }

    // ------------------------------------------------------------------ I7

    // Covers: I7 (one key sent with two different bodies)
    @Test
    void i7_keyReusedWithADifferentBody_fires() {
        CheckFixture f = new CheckFixture();
        f.log.add(complete(4, 3_000, SESSION, 0, "{\"payment\":{\"instrument\":\"pi_other\"}}", 200));

        assertFires("I7", f, "was sent with 2 different bodies");
    }

    // Covers: I7 (one approval, two complete keys)
    @Test
    void i7_twoKeysUnderOneApproval_fires() {
        CheckFixture f = new CheckFixture();
        f.log.add(complete(4, 3_000, SESSION, 1, COMPLETE_BODY, 200));

        assertFires("I7", f, "approval #1 sent complete under 2 keys");
    }

    // Covers: I7 (an old key reused after a new approval)
    @Test
    void i7_oldKeyReusedUnderANewApproval_fires() {
        CheckFixture f = new CheckFixture();
        f.approvals.add(decision(2, snapshot(SESSION, 5000, 5000), true, 1_000));
        f.log.add(complete(4, 1_100, SESSION, 1, COMPLETE_BODY, 200));
        f.log.add(complete(5, 1_200, SESSION, 0, COMPLETE_BODY, 200));

        List<String> v = fire("I7", f);

        assertThat(v).anySatisfy(d -> assertThat(d).contains("was reused under"));
        assertThat(v).anySatisfy(d -> assertThat(d).contains("went back to complete rev 0"));
    }

    // Covers: I7 (a complete with no body)
    @Test
    void i7_completeWithoutABody_fires() {
        CheckFixture f = new CheckFixture();
        f.log.set(2, entry(3, 260, "POST", "/ucp/v1/checkout-sessions/cs_1/complete",
                Optional.of(key(MutatingOp.COMPLETE, 0)), Optional.empty(), 200));

        assertFires("I7", f, "has no body");
    }

    // Covers: I7 (cross-check: the wire body is not the agent's frozen body for that key)
    @Test
    void i7_wireBodyIsNotTheFrozenBody_fires() {
        CheckFixture f = new CheckFixture();
        f.attempts.set(1, attempt("at-2", AttemptOp.COMPLETE, 0, key(MutatingOp.COMPLETE, 0),
                "{\"payment\":{\"instrument\":\"pi_frozen\"}}", 255));

        assertFires("I7", f, "is not the frozen body of attempt at-2");
    }

    // Covers: I7 (cross-check: D has no attempt for a key on the wire)
    @Test
    void i7_noStoredAttemptForTheKey_fires() {
        CheckFixture f = new CheckFixture();
        f.attempts.remove(1);

        assertFires("I7", f, "D holds no complete attempt under it");
    }

    // Covers: I7 (a resend with the same key and body, after a dropped answer, is what I7 requires)
    @Test
    void i7_sameKeySameBodyResend_isClean() {
        CheckFixture f = new CheckFixture();
        LogEntry dropped = f.log.get(2);
        f.log.set(2, new LogEntry(dropped.seq(), dropped.at(), dropped.method(), dropped.path(), dropped.requestId(),
                dropped.idempotencyKey(), dropped.bodyHmac(), 1, OptionalInt.of(200), Optional.of("forward_then_drop"
                + "_response"), Optional.of("drop"), false, false, true));
        f.log.add(complete(4, 3_000, SESSION, 0, COMPLETE_BODY, 200));

        assertThat(Checks.run(f.build(), CONTEXT).all()).isEmpty();
    }
}
