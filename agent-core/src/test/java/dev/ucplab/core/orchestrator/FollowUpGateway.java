package dev.ucplab.core.orchestrator;

import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.UpdateCheckout;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The store after a handoff (7.6 handoff follow-up): until {@link #afterHandoff} is called every call goes to the
 * delegate, which hands the intent off. After it, each {@code get} takes the next answer of the script, applied to the
 * delegate's own answer (the last one repeats), and is timed; every other call is counted as a mutating call sent after
 * the handoff, and goes to the delegate. {@link #admitting} is what {@link #admitsCalls} says (8.3).
 */
final class FollowUpGateway implements MerchantGateway {

    private final MerchantGateway delegate;
    private final Clock clock;
    private final Deque<UnaryOperator<CallOutcome<CheckoutView>>> script = new ArrayDeque<>();
    private boolean handedOff;
    volatile boolean admitting = true;
    final List<Instant> reads = new ArrayList<>();
    final List<String> mutatingAfterHandoff = new ArrayList<>();

    FollowUpGateway(MerchantGateway delegate, Clock clock) {
        this.delegate = delegate;
        this.clock = clock;
    }

    /** From now on, {@code get} answers from {@code answers}, in order; the last one repeats. */
    @SafeVarargs
    final FollowUpGateway afterHandoff(UnaryOperator<CallOutcome<CheckoutView>>... answers) {
        handedOff = true;
        script.clear();
        script.addAll(List.of(answers));
        return this;
    }

    @Override
    public boolean admitsCalls() {
        return admitting;
    }

    @Override
    public MerchantProfile discover(MerchantId merchant) {
        return delegate.discover(merchant);
    }

    @Override
    public CallOutcome<CheckoutView> create(CreateCheckout request, IdempotencyKey key) {
        sent("create");
        return delegate.create(request, key);
    }

    @Override
    public CallOutcome<CheckoutView> update(SessionId id, UpdateCheckout request, IdempotencyKey key) {
        sent("update");
        return delegate.update(id, request, key);
    }

    @Override
    public CallOutcome<CheckoutView> complete(SessionId id, FrozenBody body, IdempotencyKey key) {
        sent("complete");
        return delegate.complete(id, body, key);
    }

    @Override
    public CallOutcome<CheckoutView> cancel(SessionId id, IdempotencyKey key) {
        sent("cancel");
        return delegate.cancel(id, key);
    }

    @Override
    public CallOutcome<CheckoutView> get(SessionId id) {
        CallOutcome<CheckoutView> answer = delegate.get(id);
        if (!handedOff) {
            return answer;
        }
        reads.add(clock.instant());
        UnaryOperator<CallOutcome<CheckoutView>> next = script.size() > 1 ? script.poll() : script.peek();
        return next == null ? answer : next.apply(answer);
    }

    private void sent(String call) {
        if (handedOff) {
            mutatingAfterHandoff.add(call);
        }
    }
}
