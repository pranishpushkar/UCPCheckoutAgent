package dev.ucplab.core.orchestrator;

import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantProfile;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.UpdateCheckout;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The property tests' catch-all for I12 (14.1, H5 C2 ★): it sits where a call reaches the merchant, independent of the
 * orchestrator's own list of sends. Every create, update, complete or cancel that arrives is checked against the
 * registry entry in effect now: the entry must say owned and its fingerprint must equal the one the intent stored
 * (the intent being stepped, found through {@code acting}). A call that fails is recorded as a violation and still
 * forwarded, so the run goes on and the property reports it. Reads ({@code get}, {@code discover}) are not checked.
 */
final class OwnStoreCheckGateway implements MerchantGateway {

    private final MerchantGateway next;
    private final Function<MerchantId, Optional<MerchantEntry>> entryNow;
    private final Supplier<Optional<PurchaseIntent>> acting;
    private final Function<PurchaseIntent, List<MerchantAttempt>> attempts;
    private final List<String> violations;
    private final Runnable onMutatingCall;

    /**
     * @param acting the intent whose step or proposal is running now, read from the store
     * @param violations where a failed check is added (the run's {@code Observations.ownStore})
     * @param onMutatingCall told of every mutating call that reaches here, checked or not (for the reach numbers)
     */
    OwnStoreCheckGateway(MerchantGateway next, Function<MerchantId, Optional<MerchantEntry>> entryNow,
                         Supplier<Optional<PurchaseIntent>> acting,
                         Function<PurchaseIntent, List<MerchantAttempt>> attempts, List<String> violations,
                         Runnable onMutatingCall) {
        this.next = Objects.requireNonNull(next, "next");
        this.entryNow = Objects.requireNonNull(entryNow, "entryNow");
        this.acting = Objects.requireNonNull(acting, "acting");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.violations = Objects.requireNonNull(violations, "violations");
        this.onMutatingCall = Objects.requireNonNull(onMutatingCall, "onMutatingCall");
    }

    private void check(String call, IdempotencyKey key) {
        onMutatingCall.run();
        Optional<PurchaseIntent> intent = acting.get();
        if (intent.isEmpty()) {
            violations.add("I12: " + call + " sent with no intent stepping or proposing");
            return;
        }
        PurchaseIntent i = intent.get();
        if (attempts.apply(i).stream().noneMatch(a -> a.idempotencyKey().equals(Optional.of(key)))) {
            violations.add("I12: " + call + " for " + i.id() + " has no attempt row under its key");
        }
        Optional<MerchantEntry> entry = entryNow.apply(i.merchantId());
        if (entry.isEmpty() || !entry.get().owned()) {
            violations.add("I12: " + call + " for " + i.id() + " reached " + i.merchantId() + ", whose entry is "
                    + entry.map(e -> "not owned").orElse("missing"));
        } else if (!entry.get().fingerprint().equals(i.merchantFingerprint())) {
            violations.add("I12: " + call + " for " + i.id() + " reached " + i.merchantId() + " under entry "
                    + entry.get().fingerprint() + ", not the stored " + i.merchantFingerprint());
        }
    }

    @Override
    public MerchantProfile discover(MerchantId merchant) {
        return next.discover(merchant);
    }

    @Override
    public CallOutcome<CheckoutView> create(CreateCheckout request, IdempotencyKey key) {
        check("create", key);
        return next.create(request, key);
    }

    @Override
    public CallOutcome<CheckoutView> update(SessionId id, UpdateCheckout request, IdempotencyKey key) {
        check("update", key);
        return next.update(id, request, key);
    }

    @Override
    public CallOutcome<CheckoutView> complete(SessionId id, FrozenBody body, IdempotencyKey key) {
        check("complete", key);
        return next.complete(id, body, key);
    }

    @Override
    public CallOutcome<CheckoutView> cancel(SessionId id, IdempotencyKey key) {
        check("cancel", key);
        return next.cancel(id, key);
    }

    @Override
    public CallOutcome<CheckoutView> get(SessionId id) {
        return next.get(id);
    }
}
