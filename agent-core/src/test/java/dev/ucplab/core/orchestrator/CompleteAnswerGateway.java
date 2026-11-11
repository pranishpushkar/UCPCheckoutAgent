package dev.ucplab.core.orchestrator;

import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantMessage;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.UpdateCheckout;
import java.util.List;
import java.util.Optional;

/**
 * Answers {@code complete} with a made-up {@code Ok} (the given status and messages, on the session's current lines)
 * without sending anything to the merchant, so no order can exist. Everything else passes through.
 */
final class CompleteAnswerGateway implements MerchantGateway {

    private final MerchantGateway delegate;
    private final CheckoutStatus status;
    private final List<MerchantMessage> messages;

    CompleteAnswerGateway(MerchantGateway delegate, CheckoutStatus status, List<MerchantMessage> messages) {
        this.delegate = delegate;
        this.status = status;
        this.messages = messages;
    }

    static MerchantMessage error(String code) {
        return new MerchantMessage("error", code, Optional.empty(), "x", Optional.of("recoverable"));
    }

    static MerchantMessage warning(String code) {
        return new MerchantMessage("warning", code, Optional.empty(), "x", Optional.empty());
    }

    @Override
    public MerchantProfile discover(MerchantId merchant) {
        return delegate.discover(merchant);
    }

    @Override
    public CallOutcome<CheckoutView> create(CreateCheckout request, IdempotencyKey key) {
        return delegate.create(request, key);
    }

    @Override
    public CallOutcome<CheckoutView> update(SessionId id, UpdateCheckout request, IdempotencyKey key) {
        return delegate.update(id, request, key);
    }

    @Override
    public CallOutcome<CheckoutView> complete(SessionId id, FrozenBody body, IdempotencyKey key) {
        CheckoutView v = ((CallOutcome.Ok<CheckoutView>) delegate.get(id)).value();
        CheckoutView answer = new CheckoutView(v.id(), status, v.currency(), v.lines(), v.subtotalMinor(), v.taxMinor(),
                v.totalMinor(), Optional.empty(), v.buyer(), messages);
        return new CallOutcome.Ok<>(200, answer, messages);
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
