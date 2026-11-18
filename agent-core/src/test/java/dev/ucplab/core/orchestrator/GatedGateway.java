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

/**
 * Stands in for the gates of agent-ucp (8.3): {@link #admitting} is what {@link #admitsCalls} says, and while
 * {@link #refusing} is set every create comes back NotSent without reaching the merchant, as a breaker that refuses the
 * attempt would. Everything else goes to the delegate.
 */
final class GatedGateway implements MerchantGateway {

    private final MerchantGateway delegate;
    volatile boolean admitting = true;
    volatile boolean refusing;
    int refusedCreates;

    GatedGateway(MerchantGateway delegate) {
        this.delegate = delegate;
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
        if (refusing) {
            refusedCreates++;
            return new CallOutcome.NotSent<>("circuit_open");
        }
        return delegate.create(request, key);
    }

    @Override
    public CallOutcome<CheckoutView> update(SessionId id, UpdateCheckout request, IdempotencyKey key) {
        return delegate.update(id, request, key);
    }

    @Override
    public CallOutcome<CheckoutView> complete(SessionId id, FrozenBody body, IdempotencyKey key) {
        return delegate.complete(id, body, key);
    }

    @Override
    public CallOutcome<CheckoutView> cancel(SessionId id, IdempotencyKey key) {
        return delegate.cancel(id, key);
    }

    @Override
    public CallOutcome<CheckoutView> get(SessionId id) {
        return delegate.get(id);
    }
}
