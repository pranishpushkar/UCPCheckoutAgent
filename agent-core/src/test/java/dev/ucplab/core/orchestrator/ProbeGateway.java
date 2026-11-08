package dev.ucplab.core.orchestrator;

import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CheckoutWire;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.UpdateCheckout;
import dev.ucplab.core.merchant.fake.FaultyMerchantModel;
import dev.ucplab.core.merchant.fake.MerchantCall;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Sits on the wire in front of FaultyMerchantModel, where the fault proxy sits in section 12, and records every
 * request with what came back. For {@code complete} it also reads the merchant's own session first, so I3 can compare
 * the approved snapshot with the quote the merchant actually holds.
 */
final class ProbeGateway implements MerchantGateway {

    private final FaultyMerchantModel merchant;
    private final Clock clock;
    private final AtomicInteger seq;
    private final List<Observations.ClientCall> calls = new ArrayList<>();

    ProbeGateway(FaultyMerchantModel merchant, Clock clock, AtomicInteger seq) {
        this.merchant = merchant;
        this.clock = clock;
        this.seq = seq;
    }

    List<Observations.ClientCall> calls() {
        return List.copyOf(calls);
    }

    @Override
    public MerchantProfile discover(MerchantId id) {
        return merchant.discover(id);
    }

    @Override
    public CallOutcome<CheckoutView> create(CreateCheckout request, IdempotencyKey key) {
        return record(MerchantCall.CREATE, Optional.empty(), Optional.of(key), CheckoutWire.createBody(request),
                () -> merchant.create(request, key));
    }

    @Override
    public CallOutcome<CheckoutView> update(SessionId id, UpdateCheckout request, IdempotencyKey key) {
        return record(MerchantCall.UPDATE, Optional.of(id), Optional.of(key), CheckoutWire.updateBody(request),
                () -> merchant.update(id, request, key));
    }

    @Override
    public CallOutcome<CheckoutView> complete(SessionId id, FrozenBody body, IdempotencyKey key) {
        return record(MerchantCall.COMPLETE, Optional.of(id), Optional.of(key), body.json(),
                () -> merchant.complete(id, body, key));
    }

    @Override
    public CallOutcome<CheckoutView> cancel(SessionId id, IdempotencyKey key) {
        return record(MerchantCall.CANCEL, Optional.of(id), Optional.of(key), "", () -> merchant.cancel(id, key));
    }

    @Override
    public CallOutcome<CheckoutView> get(SessionId id) {
        return record(MerchantCall.GET, Optional.of(id), Optional.empty(), "", () -> merchant.get(id));
    }

    private CallOutcome<CheckoutView> record(
            MerchantCall call, Optional<SessionId> session, Optional<IdempotencyKey> key, String body,
            Supplier<CallOutcome<CheckoutView>> request) {
        Optional<CheckoutView> before = call == MerchantCall.COMPLETE
                ? session.flatMap(merchant::session)
                : Optional.empty();
        int n = seq.incrementAndGet();
        CallOutcome<CheckoutView> outcome = request.get();
        calls.add(new Observations.ClientCall(n, clock.instant(), call, session, key, body, before, outcome));
        return outcome;
    }
}
