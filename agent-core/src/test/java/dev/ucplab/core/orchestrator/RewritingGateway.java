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
import dev.ucplab.core.merchant.fake.MerchantCall;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Passes every call to the merchant and rewrites the answer of the chosen kinds of call, as a merchant that answers
 * oddly would (H3 P7): the call really reaches the delegate, so the session and any order are the model's, and only what
 * the orchestrator is told changes. Every answer of a rewritten kind is rewritten.
 */
final class RewritingGateway implements MerchantGateway {

    private final MerchantGateway delegate;
    private final Map<MerchantCall, UnaryOperator<CallOutcome<CheckoutView>>> rewrites = new EnumMap<>(MerchantCall.class);

    RewritingGateway(MerchantGateway delegate) {
        this.delegate = delegate;
    }

    RewritingGateway rewrite(MerchantCall call, UnaryOperator<CallOutcome<CheckoutView>> how) {
        rewrites.put(call, how);
        return this;
    }

    /** The same answer with {@code status}, no order unless {@code keepOrder}, and exactly {@code messages}. */
    static UnaryOperator<CallOutcome<CheckoutView>> as(CheckoutStatus status, boolean keepOrder, MerchantMessage... messages) {
        return outcome -> {
            if (!(outcome instanceof CallOutcome.Ok<CheckoutView> ok)) {
                return outcome;    // a call that never got an answer has nothing to rewrite
            }
            CheckoutView v = ok.value();
            List<MerchantMessage> list = List.of(messages);
            CheckoutView answer = new CheckoutView(v.id(), status, v.currency(), v.lines(), v.subtotalMinor(), v.taxMinor(),
                    v.totalMinor(), keepOrder ? v.order() : Optional.empty(), v.buyer(), list, v.fulfillment(),
                    v.fulfillmentMinor(), v.fulfillmentOffered());
            return new CallOutcome.Ok<>(ok.httpStatus(), answer, list);
        };
    }

    /** An answer that is not an Ok at all, whatever the merchant really said. */
    static UnaryOperator<CallOutcome<CheckoutView>> always(CallOutcome<CheckoutView> outcome) {
        return real -> outcome;
    }

    static MerchantMessage error(String code) {
        return new MerchantMessage("error", code, Optional.empty(), "x", Optional.of("recoverable"));
    }

    static MerchantMessage warning(String code) {
        return new MerchantMessage("warning", code, Optional.empty(), "x", Optional.empty());
    }

    private CallOutcome<CheckoutView> answer(MerchantCall call, CallOutcome<CheckoutView> real) {
        UnaryOperator<CallOutcome<CheckoutView>> how = rewrites.get(call);
        return how == null ? real : how.apply(real);
    }

    @Override
    public MerchantProfile discover(MerchantId merchant) {
        return delegate.discover(merchant);
    }

    @Override
    public CallOutcome<CheckoutView> create(CreateCheckout request, IdempotencyKey key) {
        return answer(MerchantCall.CREATE, delegate.create(request, key));
    }

    @Override
    public CallOutcome<CheckoutView> update(SessionId id, UpdateCheckout request, IdempotencyKey key) {
        return answer(MerchantCall.UPDATE, delegate.update(id, request, key));
    }

    @Override
    public CallOutcome<CheckoutView> complete(SessionId id, FrozenBody body, IdempotencyKey key) {
        return answer(MerchantCall.COMPLETE, delegate.complete(id, body, key));
    }

    @Override
    public CallOutcome<CheckoutView> cancel(SessionId id, IdempotencyKey key) {
        return answer(MerchantCall.CANCEL, delegate.cancel(id, key));
    }

    @Override
    public CallOutcome<CheckoutView> get(SessionId id) {
        return answer(MerchantCall.GET, delegate.get(id));
    }
}
