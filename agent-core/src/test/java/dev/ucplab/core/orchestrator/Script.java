package dev.ucplab.core.orchestrator;

import dev.ucplab.core.merchant.fake.Fault;
import dev.ucplab.core.merchant.fake.FaultRule;
import dev.ucplab.core.merchant.fake.FaultyMerchantModel;
import dev.ucplab.core.merchant.fake.MerchantCall;
import java.time.Duration;

/**
 * The purchase a crash matrix row runs until the armed crash fires: the merchant's faults, the steps the first
 * process takes, and what the owner does with an approval once a restarted worker has brought the intent back to
 * AWAITING_APPROVAL (the owner's own actions are not recovered by the system).
 */
enum Script {

    /** propose, approve, complete: COMPLETED. */
    HAPPY(OwnerAction.APPROVE) {
        @Override
        void run(CrashHarness h, CrashHarness.Process p) {
            h.propose(p);
            h.approve(p, true);
            h.drive(p);
        }
    },

    /** The complete is committed by the merchant but its answer is lost: RECONCILING, then the read shows completed. */
    LOST_COMPLETE_COMMITTED(OwnerAction.APPROVE) {
        @Override
        void setup(FaultyMerchantModel merchant) {
            merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.CommitThenDrop()));
        }

        @Override
        void run(CrashHarness h, CrashHarness.Process p) {
            HAPPY.run(h, p);
        }
    },

    /** The first complete never leaves: RECONCILING sees ready_for_complete and resends after the quiet period. */
    RESEND(OwnerAction.APPROVE) {
        @Override
        void setup(FaultyMerchantModel merchant) {
            merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.NotSent()));
        }

        @Override
        void run(CrashHarness h, CrashHarness.Process p) {
            HAPPY.run(h, p);
        }
    },

    /** The owner rejects: REJECTED, with the session to cancel. */
    REJECT(OwnerAction.REJECT) {
        @Override
        void run(CrashHarness h, CrashHarness.Process p) {
            h.propose(p);
            h.approve(p, false);
            h.drive(p);
        }
    },

    /** Nobody decides: the approval expires and the intent is REJECTED. */
    EXPIRE(OwnerAction.NONE) {
        @Override
        void run(CrashHarness h, CrashHarness.Process p) {
            h.propose(p);
            h.clock.advance(Duration.ofMinutes(10).plusSeconds(1));
            h.drive(p);
        }
    },

    /** The price changes before complete: a re-quote, and a second approval from the owner. */
    REQUOTE(OwnerAction.APPROVE) {
        @Override
        void setup(FaultyMerchantModel merchant) {
            merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.PriceChange("item_123", 2600)));
        }

        @Override
        void run(CrashHarness h, CrashHarness.Process p) {
            HAPPY.run(h, p);
        }
    },

    /** The complete never leaves and the merchant cannot be read: RECONCILING until the deadline, then ESCALATED. */
    ESCALATE(OwnerAction.APPROVE) {
        @Override
        void setup(FaultyMerchantModel merchant) {
            merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.NotSent()));
            merchant.addRule(FaultRule.every(MerchantCall.GET, new Fault.NotSent()));
        }

        @Override
        void run(CrashHarness h, CrashHarness.Process p) {
            HAPPY.run(h, p);
        }
    };

    enum OwnerAction { APPROVE, REJECT, NONE }

    final OwnerAction ownerAction;

    Script(OwnerAction ownerAction) {
        this.ownerAction = ownerAction;
    }

    /** Faults the merchant applies in this scenario. */
    void setup(FaultyMerchantModel merchant) {
    }

    /** The steps of the original process; they stop at the crash. */
    abstract void run(CrashHarness h, CrashHarness.Process p);
}
