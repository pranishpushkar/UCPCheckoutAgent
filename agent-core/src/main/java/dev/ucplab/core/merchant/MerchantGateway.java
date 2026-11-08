package dev.ucplab.core.merchant;

/**
 * Port to a merchant's UCP endpoints (Appendix B). Implemented by agent-ucp, and by FaultyMerchantModel in tests.
 * Calls report network failure as an outcome, not an exception (8.1).
 */
public interface MerchantGateway {

    MerchantProfile discover(MerchantId merchant);

    CallOutcome<CheckoutView> create(CreateCheckout request, IdempotencyKey key);

    CallOutcome<CheckoutView> update(SessionId id, UpdateCheckout request, IdempotencyKey key);

    CallOutcome<CheckoutView> complete(SessionId id, FrozenBody body, IdempotencyKey key);

    CallOutcome<CheckoutView> cancel(SessionId id, IdempotencyKey key);

    CallOutcome<CheckoutView> get(SessionId id);
}
