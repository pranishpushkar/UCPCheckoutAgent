package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.orchestrator.RewritingGateway.as;
import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.approval.ApprovalDecision;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.core.intent.InMemoryIntentRepository;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.fake.Fault;
import dev.ucplab.core.merchant.fake.FaultRule;
import dev.ucplab.core.merchant.fake.MerchantCall;
import dev.ucplab.core.policy.PolicyConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * Stage 5: the two P7 equivalents the owner refused (24 and 30), killed by tests.
 */
class OrchestratorThirdRoundMutationTest {

    // Covers: 7.7, I1, 9.1 P5. Kills DefaultOrchestrator.propose RemoveConditional_EQUAL_ELSE (the `existing.isPresent()` fast
    // path, the former equivalent 24). With policy counting the user's own intents and a limit of one proposal an hour, the
    // repeat of an active proposal must return the first intent: without the fast path it re-runs policy, which counts the
    // first intent, denies the repeat, and stores a second, REJECTED intent
    @Test
    void aRepeatedProposal_returnsTheFirstIntent_andIsNotJudgedAgainstItself() {
        Lab lab = new Lab();
        lab.countUsage = true;
        lab.policyConfig = new PolicyConfig(20_000, 50_000, 5, 5, 1, true, 2);
        Lab.Worker w = lab.worker("worker-a");

        ProposalResult first = lab.propose(w, TWO_SHIRTS);
        lab.clock.advance(Duration.ofSeconds(1));   // the usage window is [from, to): the first intent is counted from now on
        ProposalResult second = lab.propose(w, TWO_SHIRTS);

        assertThat(first.state()).isEqualTo(IntentState.AWAITING_APPROVAL);
        assertThat(second.intentId()).isEqualTo(first.intentId());
        assertThat(second.state()).isEqualTo(IntentState.AWAITING_APPROVAL);
        assertThat(((InMemoryIntentRepository) lab.intents).ownedBy(Lab.ALICE)).as("one intent, not a rejected second").hasSize(1);
    }

    /** Reads the lab's clock, and after {@link #arm()} moves a fixed step forward on every read. */
    private static final class TickingClock extends Clock {
        private final Clock inner;
        private final Duration step;
        private long ticks = -1;
        private Instant last;

        TickingClock(Clock inner, Duration step) {
            this.inner = inner;
            this.step = step;
        }

        synchronized void arm() {
            ticks = 0;
        }

        synchronized Instant last() {
            return last;
        }

        @Override
        public ZoneId getZone() {
            return inner.getZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public synchronized Instant instant() {
            Instant now = inner.instant();
            if (ticks >= 0) {
                now = now.plus(step.multipliedBy(ticks++));
            }
            last = now;
            return last;
        }
    }

    // Covers: 7.5 "Who waits". Kills DefaultOrchestrator.nextAttemptAt RemoveConditional_EQUAL_IF (the `!now.isAfter(deadline)`
    // operand, the former equivalent 30). A clock that moves a second on every read lets the deadline pass between the step
    // (which still says RECONCILING) and the time handed to the caller: the caller must then be told a time that is not in the
    // past, never the deadline that has gone by
    @Test
    void aDeadlineThatPassesDuringTheStep_neverSchedulesTheNextPollInThePast() {
        Lab lab = new Lab();
        TickingClock ticking = new TickingClock(lab.clock, Duration.ofSeconds(1));
        lab.orchestratorClock = ticking;
        RewritingGateway gateway = new RewritingGateway(lab.merchant).rewrite(MerchantCall.GET,
                as(CheckoutStatus.COMPLETE_IN_PROGRESS, false));
        Lab.Worker w = lab.worker("worker-a", gateway);
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.NotSent()));
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        assertThat(lab.decide(w, id, true)).isInstanceOf(ApprovalDecision.Approved.class);
        Instant since = lab.clock.instant();
        Instant deadline = since.plus(lab.config.reconcileDeadline());

        int crossings = 0;
        for (int offset = 0; offset < 80; offset++) {
            lab.clock.advance(Duration.ofSeconds(1));
            ticking.arm();
            StepResult r = w.orchestrator().advance(id);
            if (r.after() != IntentState.RECONCILING) {
                break;
            }
            assertThat(r.nextAttemptAt()).as("offset %d s", offset).isPresent();
            assertThat(r.nextAttemptAt().get()).as("offset %d s: not before the last read of the clock", offset)
                    .isAfterOrEqualTo(ticking.last());
            if (ticking.last().isAfter(deadline)) {
                crossings++;
            }
        }
        assertThat(crossings).as("a step that ended RECONCILING after the deadline was reached").isGreaterThan(0);
        assertThat(lab.intents.attempts(id).stream().filter(a -> a.op() == AttemptOp.COMPLETE).count()).isEqualTo(1);
    }
}
