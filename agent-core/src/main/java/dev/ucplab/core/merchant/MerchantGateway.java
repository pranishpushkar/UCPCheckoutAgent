package dev.ucplab.core.merchant;

/**
 * Port to a merchant's UCP endpoints (Appendix B). Implemented by agent-ucp, and by FaultyMerchantModel in tests.
 * Calls report network failure as an outcome, not an exception (8.1).
 */
public interface MerchantGateway {

    /**
     * @throws IllegalArgumentException if this gateway is not bound to {@code merchant} (8.5)
     * @throws DiscoveryFailedException if the profile could not be fetched or read
     */
    MerchantProfile discover(MerchantId merchant);

    CallOutcome<CheckoutView> create(CreateCheckout request, IdempotencyKey key);

    CallOutcome<CheckoutView> update(SessionId id, UpdateCheckout request, IdempotencyKey key);

    CallOutcome<CheckoutView> complete(SessionId id, FrozenBody body, IdempotencyKey key);

    CallOutcome<CheckoutView> cancel(SessionId id, IdempotencyKey key);

    CallOutcome<CheckoutView> get(SessionId id);
}
