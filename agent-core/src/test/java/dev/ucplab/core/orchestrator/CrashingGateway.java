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
import java.util.function.Supplier;

/**
 * Wraps a {@link MerchantGateway} to simulate a process dying around the n-th call of a method: before the request
 * leaves (7.3 between steps 3 and 4) or after the merchant processed it (between steps 4 and 5). {@link Op} has one
 * constant per method of the port. Unlike {@link InterceptingGateway} it counts calls, so a row can name the resend.
 */
final class CrashingGateway implements MerchantGateway {

    enum Op { DISCOVER, CREATE, UPDATE, COMPLETE, CANCEL, GET }

    private final MerchantGateway delegate;
    private final CrashPlan<Op> plan;

    CrashingGateway(MerchantGateway delegate, CrashPlan<Op> plan) {
        this.delegate = delegate;
        this.plan = plan;
    }

    private <T> T call(Op op, Supplier<T> body) {
        plan.before(op);
        T result = body.get();
        plan.after(op);
        return result;
    }

    @Override
    public MerchantProfile discover(MerchantId merchant) {
        return call(Op.DISCOVER, () -> delegate.discover(merchant));
    }

    @Override
    public CallOutcome<CheckoutView> create(CreateCheckout request, IdempotencyKey key) {
        return call(Op.CREATE, () -> delegate.create(request, key));
    }

    @Override
    public CallOutcome<CheckoutView> update(SessionId id, UpdateCheckout request, IdempotencyKey key) {
        return call(Op.UPDATE, () -> delegate.update(id, request, key));
    }

    @Override
    public CallOutcome<CheckoutView> complete(SessionId id, FrozenBody body, IdempotencyKey key) {
        return call(Op.COMPLETE, () -> delegate.complete(id, body, key));
    }

    @Override
    public CallOutcome<CheckoutView> cancel(SessionId id, IdempotencyKey key) {
        return call(Op.CANCEL, () -> delegate.cancel(id, key));
    }

    @Override
    public CallOutcome<CheckoutView> get(SessionId id) {
        return call(Op.GET, () -> delegate.get(id));
    }
}
