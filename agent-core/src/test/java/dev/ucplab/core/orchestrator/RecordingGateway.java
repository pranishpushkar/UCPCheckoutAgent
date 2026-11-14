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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** A {@link MerchantGateway} that notes which of the five checkout calls reached it, then passes the call on. */
final class RecordingGateway implements MerchantGateway {

    private final MerchantGateway delegate;
    private final List<String> calls = new CopyOnWriteArrayList<>();

    RecordingGateway(MerchantGateway delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** The calls so far, in order: create, update, complete, cancel, get. */
    List<String> calls() {
        return List.copyOf(calls);
    }

    @Override
    public MerchantProfile discover(MerchantId merchant) {
        return delegate.discover(merchant);
    }

    @Override
    public CallOutcome<CheckoutView> create(CreateCheckout request, IdempotencyKey key) {
        calls.add("create");
        return delegate.create(request, key);
    }

    @Override
    public CallOutcome<CheckoutView> update(SessionId id, UpdateCheckout request, IdempotencyKey key) {
        calls.add("update");
        return delegate.update(id, request, key);
    }

    @Override
    public CallOutcome<CheckoutView> complete(SessionId id, FrozenBody body, IdempotencyKey key) {
        calls.add("complete");
        return delegate.complete(id, body, key);
    }

    @Override
    public CallOutcome<CheckoutView> cancel(SessionId id, IdempotencyKey key) {
        calls.add("cancel");
        return delegate.cancel(id, key);
    }

    @Override
    public CallOutcome<CheckoutView> get(SessionId id) {
        calls.add("get");
        return delegate.get(id);
    }
}
