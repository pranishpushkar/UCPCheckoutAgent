package dev.ucplab.core.orchestrator;

import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.LineView;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantMessage;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.UpdateCheckout;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Behaves like the reference merchant when stock is short (MerchantCore.resolveLines): it answers a create or update
 * with the quantity cut to {@code quantity} on every line, and, if {@code warn}, a {@code quantity_adjusted} warning.
 * Everything else passes through to the delegate.
 */
final class QuantityAdjustingGateway implements MerchantGateway {

    private final MerchantGateway delegate;
    private final int quantity;
    private final boolean warn;
    private final boolean updatesOnly;

    QuantityAdjustingGateway(MerchantGateway delegate, int quantity, boolean warn, boolean updatesOnly) {
        this.delegate = delegate;
        this.quantity = quantity;
        this.warn = warn;
        this.updatesOnly = updatesOnly;
    }

    private CallOutcome<CheckoutView> adjust(CallOutcome<CheckoutView> outcome) {
        if (!(outcome instanceof CallOutcome.Ok<CheckoutView> ok)) {
            return outcome;
        }
        CheckoutView v = ok.value();
        List<LineView> lines = v.lines().stream()
                .map(l -> new LineView(l.itemId(), Math.min(l.quantity(), quantity), l.unitPriceMinor())).toList();
        List<MerchantMessage> messages = new ArrayList<>(v.messages());
        if (warn) {
            messages.add(new MerchantMessage("warning", "quantity_adjusted", Optional.of("$.line_items[0].quantity"),
                    "Quantity adjusted", Optional.empty()));
        }
        CheckoutView adjusted = new CheckoutView(v.id(), v.status(), v.currency(), lines, v.subtotalMinor(),
                v.taxMinor(), v.totalMinor(), v.order(), messages);
        return new CallOutcome.Ok<>(ok.httpStatus(), adjusted, messages);
    }

    @Override
    public MerchantProfile discover(MerchantId merchant) {
        return delegate.discover(merchant);
    }

    @Override
    public CallOutcome<CheckoutView> create(CreateCheckout request, IdempotencyKey key) {
        CallOutcome<CheckoutView> outcome = delegate.create(request, key);
        return updatesOnly ? outcome : adjust(outcome);
    }

    @Override
    public CallOutcome<CheckoutView> update(SessionId id, UpdateCheckout request, IdempotencyKey key) {
        return adjust(delegate.update(id, request, key));
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
