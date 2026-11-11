package dev.ucplab.core.merchant;

/**
 * Thrown by {@link MerchantGateway#discover} when the merchant's profile could not be fetched or read (section 19). The
 * message never contains a response body. Unchecked: {@code discover} returns a profile, not an outcome (8.5).
 */
public final class DiscoveryFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DiscoveryFailedException(String message) {
        super(message);
    }
}
