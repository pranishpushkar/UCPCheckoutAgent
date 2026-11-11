package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.intent.IntentState.COMPLETED;
import static dev.ucplab.core.intent.IntentState.ESCALATED;
import static dev.ucplab.core.intent.IntentState.FAILED_NOT_CHARGED;
import static dev.ucplab.core.intent.IntentState.REJECTED;
import static dev.ucplab.core.orchestrator.CrashPlan.Phase.AFTER;
import static dev.ucplab.core.orchestrator.CrashPlan.Phase.BEFORE;

import dev.ucplab.core.intent.IntentState;
import java.util.List;

/**
 * The crash matrix of m3-plan.md heading 8: one constant per row, in the plan's order. A row names the scenario that
 * reaches the boundary and the crash points on it; each point says where the process dies and what the intent must
 * look like once a new worker, started after the lease has lapsed, has recovered it, together with how many orders the
 * merchant holds. A null state means no intent exists.
 */
enum Boundary {

    R1_PROPOSE_INSERT(1, "propose: insert", "I5, 7.7", Script.HAPPY,
            Point.intent(CrashingIntentRepository.Op.FIND_ACTIVE_BY_DEDUPE_KEY, BEFORE, 1, null, 0),
            Point.intent(CrashingIntentRepository.Op.INSERT, BEFORE, 1, null, 0),
            Point.intent(CrashingIntentRepository.Op.INSERT, AFTER, 1, COMPLETED, 1)),

    R2_PROPOSAL_EVENTS(2, "proposal events", "FR8", Script.HAPPY,
            Point.intent(CrashingIntentRepository.Op.APPEND_EVENT, BEFORE, 1, COMPLETED, 1),
            Point.intent(CrashingIntentRepository.Op.APPEND_EVENT, AFTER, 1, COMPLETED, 1)),

    R3_RECORD_CREATE(3, "recordAttempt(create)", "I1, 7.6", Script.HAPPY,
            Point.intent(CrashingIntentRepository.Op.RECORD_ATTEMPT, BEFORE, 1, COMPLETED, 1),
            Point.intent(CrashingIntentRepository.Op.RECORD_ATTEMPT, AFTER, 1, FAILED_NOT_CHARGED, 0)),

    R4_MERCHANT_CREATE(4, "merchant.create", "I1, I4", Script.HAPPY,
            Point.gateway(CrashingGateway.Op.CREATE, BEFORE, 1, FAILED_NOT_CHARGED, 0),
            Point.gateway(CrashingGateway.Op.CREATE, AFTER, 1, FAILED_NOT_CHARGED, 0)),

    R5_APPROVAL_OPEN(5, "approvalService.open", "I3", Script.HAPPY,
            Point.approval(CrashingApprovalRepository.Op.LIST_FOR_SYSTEM, BEFORE, 1, FAILED_NOT_CHARGED, 0),
            Point.approval(CrashingApprovalRepository.Op.INSERT, BEFORE, 1, FAILED_NOT_CHARGED, 0),
            Point.approval(CrashingApprovalRepository.Op.INSERT, AFTER, 1, FAILED_NOT_CHARGED, 0)),

    R6_RESOLVE_TO_AWAITING(6, "resolve to AWAITING_APPROVAL", "I3", Script.HAPPY,
            Point.intent(CrashingIntentRepository.Op.RESOLVE_ATTEMPT, BEFORE, 1, FAILED_NOT_CHARGED, 0),
            Point.intent(CrashingIntentRepository.Op.RESOLVE_ATTEMPT, AFTER, 1, COMPLETED, 1)),

    R7_DECIDE(7, "decide: intent update, approval transition", "I3, I10, I1", Script.HAPPY,
            Point.intent(CrashingIntentRepository.Op.FIND, BEFORE, 2, COMPLETED, 1),
            Point.approval(CrashingApprovalRepository.Op.FIND_LATEST, BEFORE, 2, COMPLETED, 1),
            Point.intent(CrashingIntentRepository.Op.CLAIM, BEFORE, 2, COMPLETED, 1),
            Point.intent(CrashingIntentRepository.Op.UPDATE, BEFORE, 1, COMPLETED, 1),
            Point.intent(CrashingIntentRepository.Op.UPDATE, AFTER, 1, COMPLETED, 1),
            Point.approval(CrashingApprovalRepository.Op.TRANSITION, BEFORE, 1, COMPLETED, 1),
            Point.approval(CrashingApprovalRepository.Op.TRANSITION, AFTER, 1, COMPLETED, 1)),

    R8_EXPIRY(8, "expiry: intent then approval", "I5", Script.EXPIRE,
            Point.approval(CrashingApprovalRepository.Op.FIND_LATEST_FOR_SYSTEM, BEFORE, 1, REJECTED, 0),
            Point.intent(CrashingIntentRepository.Op.UPDATE, BEFORE, 1, REJECTED, 0),
            Point.intent(CrashingIntentRepository.Op.UPDATE, AFTER, 1, REJECTED, 0),
            Point.approval(CrashingApprovalRepository.Op.TRANSITION, BEFORE, 1, REJECTED, 0),
            Point.approval(CrashingApprovalRepository.Op.TRANSITION, AFTER, 1, REJECTED, 0)),

    R9_SEND_COMPLETE(9, "sendComplete: recordAttempt and COMPLETING", "I1, I7", Script.HAPPY,
            Point.intent(CrashingIntentRepository.Op.ATTEMPTS, BEFORE, 2, COMPLETED, 1),
            Point.approval(CrashingApprovalRepository.Op.FIND_LATEST_FOR_SYSTEM, BEFORE, 1, COMPLETED, 1),
            Point.intent(CrashingIntentRepository.Op.RECORD_ATTEMPT, BEFORE, 2, COMPLETED, 1),
            Point.intent(CrashingIntentRepository.Op.RECORD_ATTEMPT, AFTER, 2, COMPLETED, 1)),

    R10_CONSUME_APPROVAL(10, "approval to CONSUMED", "I3", Script.HAPPY,
            Point.approval(CrashingApprovalRepository.Op.TRANSITION, BEFORE, 2, COMPLETED, 1),
            Point.approval(CrashingApprovalRepository.Op.TRANSITION, AFTER, 2, COMPLETED, 1)),

    R11_MERCHANT_COMPLETE(11, "merchant.complete", "I1, I4, I7, S9", Script.HAPPY,
            Point.gateway(CrashingGateway.Op.COMPLETE, BEFORE, 1, COMPLETED, 1),
            Point.gateway(CrashingGateway.Op.COMPLETE, AFTER, 1, COMPLETED, 1)),

    R12_RESOLVE_COMPLETED(12, "resolve to COMPLETED or RECONCILING", "I1", Script.HAPPY,
            Point.intent(CrashingIntentRepository.Op.RESOLVE_ATTEMPT, BEFORE, 2, COMPLETED, 1),
            Point.intent(CrashingIntentRepository.Op.RESOLVE_ATTEMPT, AFTER, 2, COMPLETED, 1),
            Point.intent(CrashingIntentRepository.Op.FIND_FOR_SYSTEM, BEFORE, 1, COMPLETED, 1)),

    R13_RECONCILE_GET(13, "reconcile get", "I5", Script.LOST_COMPLETE_COMMITTED,
            Point.gateway(CrashingGateway.Op.GET, BEFORE, 1, COMPLETED, 1),
            Point.gateway(CrashingGateway.Op.GET, AFTER, 1, COMPLETED, 1)),

    R14_SETTLE_THEN_UPDATE(14, "settle then update", "7.4 guard", Script.LOST_COMPLETE_COMMITTED,
            Point.intent(CrashingIntentRepository.Op.RESOLVE_ATTEMPTS, BEFORE, 1, COMPLETED, 1),
            Point.intent(CrashingIntentRepository.Op.RESOLVE_ATTEMPTS, AFTER, 1, COMPLETED, 1),
            Point.intent(CrashingIntentRepository.Op.UPDATE, BEFORE, 2, COMPLETED, 1),
            Point.intent(CrashingIntentRepository.Op.UPDATE, AFTER, 2, COMPLETED, 1)),

    R15_RESEND(15, "resend: update, recordAttempt, call, resolve", "I7", Script.RESEND,
            Point.intent(CrashingIntentRepository.Op.UPDATE, BEFORE, 2, COMPLETED, 1),
            Point.intent(CrashingIntentRepository.Op.UPDATE, AFTER, 2, COMPLETED, 1),
            Point.intent(CrashingIntentRepository.Op.RECORD_ATTEMPT, BEFORE, 3, COMPLETED, 1),
            Point.intent(CrashingIntentRepository.Op.RECORD_ATTEMPT, AFTER, 3, COMPLETED, 1),
            Point.gateway(CrashingGateway.Op.COMPLETE, BEFORE, 2, COMPLETED, 1),
            Point.gateway(CrashingGateway.Op.COMPLETE, AFTER, 2, COMPLETED, 1),
            Point.intent(CrashingIntentRepository.Op.RESOLVE_ATTEMPT, BEFORE, 3, COMPLETED, 1),
            Point.intent(CrashingIntentRepository.Op.RESOLVE_ATTEMPT, AFTER, 3, COMPLETED, 1)),

    R16_REQUOTE_UPDATE(16, "re-quote update attempt", "I1", Script.REQUOTE,
            Point.intent(CrashingIntentRepository.Op.RECORD_ATTEMPT, BEFORE, 3, COMPLETED, 1),
            Point.intent(CrashingIntentRepository.Op.RECORD_ATTEMPT, AFTER, 3, FAILED_NOT_CHARGED, 0),
            Point.gateway(CrashingGateway.Op.UPDATE, BEFORE, 1, FAILED_NOT_CHARGED, 0),
            Point.gateway(CrashingGateway.Op.UPDATE, AFTER, 1, FAILED_NOT_CHARGED, 0)),

    R17_CANCEL(17, "cancel: attempt, call, resolve", "7.6", Script.REJECT,
            Point.intent(CrashingIntentRepository.Op.UPDATE, BEFORE, 1, REJECTED, 0),
            Point.intent(CrashingIntentRepository.Op.UPDATE, AFTER, 1, REJECTED, 0),
            Point.intent(CrashingIntentRepository.Op.RECORD_ATTEMPT, BEFORE, 2, REJECTED, 0),
            Point.intent(CrashingIntentRepository.Op.RECORD_ATTEMPT, AFTER, 2, REJECTED, 0),
            Point.gateway(CrashingGateway.Op.CANCEL, BEFORE, 1, REJECTED, 0),
            Point.gateway(CrashingGateway.Op.CANCEL, AFTER, 1, REJECTED, 0),
            Point.intent(CrashingIntentRepository.Op.RESOLVE_ATTEMPT, BEFORE, 2, REJECTED, 0),
            Point.intent(CrashingIntentRepository.Op.RESOLVE_ATTEMPT, AFTER, 2, REJECTED, 0)),

    R18_ESCALATE(18, "escalate", "I5", Script.ESCALATE,
            Point.intent(CrashingIntentRepository.Op.UPDATE, BEFORE, 2, ESCALATED, 0),
            Point.intent(CrashingIntentRepository.Op.UPDATE, AFTER, 2, ESCALATED, 0)),

    R19_LEASE(19, "lease claim and renew", "I5", Script.HAPPY,
            Point.intent(CrashingIntentRepository.Op.CLAIM, BEFORE, 1, COMPLETED, 1),
            Point.intent(CrashingIntentRepository.Op.CLAIM, AFTER, 1, COMPLETED, 1),
            // A worker that dies during a recovery pass while holding the lease it just took: the next one waits it out.
            Point.recoveryIntent(CrashingIntentRepository.Op.CLAIM_RECOVERABLE, BEFORE, 1, FAILED_NOT_CHARGED, 0),
            Point.recoveryIntent(CrashingIntentRepository.Op.CLAIM_RECOVERABLE, AFTER, 1, FAILED_NOT_CHARGED, 0),
            Point.recoveryIntent(CrashingIntentRepository.Op.RENEW_LEASE, BEFORE, 1, FAILED_NOT_CHARGED, 0),
            Point.recoveryIntent(CrashingIntentRepository.Op.RENEW_LEASE, AFTER, 1, FAILED_NOT_CHARGED, 0)),

    /** Needs a statement failpoint inside the SQL adapter, which an in-memory store does not have: no points here. */
    R20_SQL_STATEMENT_FAILPOINT(20, "SQL: statement failpoint inside recordAttempt and resolveAttempt", "7.3 steps 3 and 5",
            Script.HAPPY);

    enum Kind { INTENT, APPROVAL, GATEWAY }

    /**
     * One place to crash. {@code op} is the name of a constant of the target's Op enum.
     *
     * @param recovery the crash happens in the first recovery pass instead of in the original process; the original
     *     process then dies at the create call (so there is an intent to recover)
     * @param expected the state of the intent after recovery, or null when no intent exists
     * @param orders how many orders the merchant must hold afterwards
     */
    record Point(Kind kind, String op, CrashPlan.Phase phase, int nth, boolean recovery, IntentState expected, int orders) {

        static Point intent(CrashingIntentRepository.Op op, CrashPlan.Phase phase, int nth, IntentState expected, int orders) {
            return new Point(Kind.INTENT, op.name(), phase, nth, false, expected, orders);
        }

        static Point recoveryIntent(
                CrashingIntentRepository.Op op, CrashPlan.Phase phase, int nth, IntentState expected, int orders) {
            return new Point(Kind.INTENT, op.name(), phase, nth, true, expected, orders);
        }

        static Point approval(
                CrashingApprovalRepository.Op op, CrashPlan.Phase phase, int nth, IntentState expected, int orders) {
            return new Point(Kind.APPROVAL, op.name(), phase, nth, false, expected, orders);
        }

        static Point gateway(CrashingGateway.Op op, CrashPlan.Phase phase, int nth, IntentState expected, int orders) {
            return new Point(Kind.GATEWAY, op.name(), phase, nth, false, expected, orders);
        }

        @Override
        public String toString() {
            return (recovery ? "recovery " : "") + kind + "." + op + " " + phase + " #" + nth;
        }
    }

    final int row;
    final String title;
    final String protects;
    final Script script;
    final List<Point> points;

    Boundary(int row, String title, String protects, Script script, Point... points) {
        this.row = row;
        this.title = title;
        this.protects = protects;
        this.script = script;
        this.points = List.of(points);
    }
}
