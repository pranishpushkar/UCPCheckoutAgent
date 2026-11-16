package dev.ucplab.core.orchestrator;

import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.ContinueUrl;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.UpdateCheckout;
import dev.ucplab.core.merchant.LineView;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.ToLongFunction;

/**
 * Passes every call to the merchant and adds a continue_url on {@code shopDomain} to every answer, as a handoff store
 * does (7.2 hand_off, H5 C7a). A GET answer shows each line at the store's price now ({@code priceNow}), as a live
 * cart does, with the subtotal, tax and total to match, so a price change after the quote is a hand_off_changed. The
 * store of every even-numbered session (the second of a run) keeps failing its reads: each GET reaches the merchant and
 * its answer is lost (Uncertain), so those intents meet the 7.2 handoff_timeout. Only what the orchestrator is told changes.
 */
final class ContinueUrlGateway implements MerchantGateway {

    private final MerchantGateway delegate;
    private final String shopDomain;
    private final ToLongFunction<String> priceNow;

    ContinueUrlGateway(MerchantGateway delegate, String shopDomain, ToLongFunction<String> priceNow) {
        this.delegate = delegate;
        this.shopDomain = shopDomain;
        this.priceNow = priceNow;
    }

    private CallOutcome<CheckoutView> repriced(CallOutcome<CheckoutView> outcome) {
        if (!(outcome instanceof CallOutcome.Ok<CheckoutView> ok)) {
            return outcome;
        }
        CheckoutView v = ok.value();
        List<LineView> lines = new ArrayList<>();
        for (LineView l : v.lines()) {
            lines.add(new LineView(l.itemId(), l.quantity(), priceNow.applyAsLong(l.itemId()), l.lineId()));
        }
        long subtotal = lines.stream().mapToLong(l -> l.unitPriceMinor() * l.quantity()).sum();
        long tax = (subtotal * 8 + 50) / 100;   // the lab's tax: 8%, rounded half up on the subtotal (A.2)
        return new CallOutcome.Ok<>(ok.httpStatus(), new CheckoutView(v.id(), v.status(), v.currency(), lines, subtotal,
                tax, subtotal + tax + v.fulfillmentMinor(), v.order(), v.buyer(), v.messages(), v.fulfillment(),
                v.fulfillmentMinor(), v.fulfillmentOffered(), v.continueUrl()), ok.messages());
    }

    private CallOutcome<CheckoutView> withUrl(CallOutcome<CheckoutView> outcome) {
        if (!(outcome instanceof CallOutcome.Ok<CheckoutView> ok)) {
            return outcome;
        }
        CheckoutView v = ok.value();
        ContinueUrl url = new ContinueUrl(URI.create("https://" + shopDomain + "/cart/c/" + v.id().value() + "?key=k"));
        return new CallOutcome.Ok<>(ok.httpStatus(), new CheckoutView(v.id(), v.status(), v.currency(), v.lines(),
                v.subtotalMinor(), v.taxMinor(), v.totalMinor(), v.order(), v.buyer(), v.messages(), v.fulfillment(),
                v.fulfillmentMinor(), v.fulfillmentOffered(), Optional.of(url)), ok.messages());
    }

    @Override
    public MerchantProfile discover(MerchantId merchant) {
        return delegate.discover(merchant);
    }

    @Override
    public CallOutcome<CheckoutView> create(CreateCheckout request, IdempotencyKey key) {
        return withUrl(delegate.create(request, key));
    }

    @Override
    public CallOutcome<CheckoutView> update(SessionId id, UpdateCheckout request, IdempotencyKey key) {
        return withUrl(delegate.update(id, request, key));
    }

    @Override
    public CallOutcome<CheckoutView> complete(SessionId id, FrozenBody body, IdempotencyKey key) {
        return withUrl(delegate.complete(id, body, key));
    }

    @Override
    public CallOutcome<CheckoutView> cancel(SessionId id, IdempotencyKey key) {
        return withUrl(delegate.cancel(id, key));
    }

    @Override
    public CallOutcome<CheckoutView> get(SessionId id) {
        CallOutcome<CheckoutView> read = delegate.get(id);
        return keepsFailing(id) ? new CallOutcome.Uncertain<>("read lost") : withUrl(repriced(read));
    }

    /** Whether the session's store keeps failing its reads: its number, after the last '-', is even. */
    static boolean keepsFailing(SessionId id) {
        String v = id.value();
        int dash = v.lastIndexOf('-');
        if (dash < 0) {
            return false;
        }
        try {
            return Integer.parseInt(v.substring(dash + 1)) % 2 == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
