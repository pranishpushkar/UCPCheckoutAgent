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
import dev.ucplab.core.merchant.fake.MerchantCall;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Wraps a gateway to simulate a process dying around a merchant call (7.3 crash points) or something happening while
 * the call is in flight (7.6). Each interception fires once.
 */
final class InterceptingGateway implements MerchantGateway {

    /** A kill -9: thrown through the orchestrator so none of its later writes happen. */
    static final class SimulatedCrash extends Error {
        private static final long serialVersionUID = 1L;

        SimulatedCrash(String where) {
            super("simulated crash " + where);
        }
    }

    private final MerchantGateway delegate;
    private final Map<MerchantCall, Boolean> crashBefore = new EnumMap<>(MerchantCall.class);
    private final Map<MerchantCall, Boolean> crashAfter = new EnumMap<>(MerchantCall.class);
    private final Map<MerchantCall, Runnable> during = new EnumMap<>(MerchantCall.class);

    InterceptingGateway(MerchantGateway delegate) {
        this.delegate = delegate;
    }

    /** Dies after the attempt row is written but before the request leaves (between 7.3 steps 3 and 4). */
    void crashBeforeNext(MerchantCall call) {
        crashBefore.put(call, true);
    }

    /** Dies after the merchant processed the request but before the outcome is persisted (between steps 4 and 5). */
    void crashAfterNext(MerchantCall call) {
        crashAfter.put(call, true);
    }

    /** Runs {@code action} after the merchant processed the next such call, before the orchestrator sees the result. */
    void duringNext(MerchantCall call, Runnable action) {
        during.put(call, action);
    }

    @Override
    public MerchantProfile discover(MerchantId merchant) {
        return delegate.discover(merchant);
    }

    @Override
    public CallOutcome<CheckoutView> create(CreateCheckout request, IdempotencyKey key) {
        return intercept(MerchantCall.CREATE, () -> delegate.create(request, key));
    }

    @Override
    public CallOutcome<CheckoutView> update(SessionId id, UpdateCheckout request, IdempotencyKey key) {
        return intercept(MerchantCall.UPDATE, () -> delegate.update(id, request, key));
    }

    @Override
    public CallOutcome<CheckoutView> complete(SessionId id, FrozenBody body, IdempotencyKey key) {
        return intercept(MerchantCall.COMPLETE, () -> delegate.complete(id, body, key));
    }

    @Override
    public CallOutcome<CheckoutView> cancel(SessionId id, IdempotencyKey key) {
        return intercept(MerchantCall.CANCEL, () -> delegate.cancel(id, key));
    }

    @Override
    public CallOutcome<CheckoutView> get(SessionId id) {
        return intercept(MerchantCall.GET, () -> delegate.get(id));
    }

    private CallOutcome<CheckoutView> intercept(MerchantCall call, Supplier<CallOutcome<CheckoutView>> request) {
        if (crashBefore.remove(call) != null) {
            throw new SimulatedCrash("before " + call);
        }
        CallOutcome<CheckoutView> outcome = request.get();
        Runnable action = during.remove(call);
        if (action != null) {
            action.run();
        }
        if (crashAfter.remove(call) != null) {
            throw new SimulatedCrash("after " + call);
        }
        return outcome;
    }
}
