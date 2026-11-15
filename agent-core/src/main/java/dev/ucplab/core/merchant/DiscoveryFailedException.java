package dev.ucplab.core.merchant;

import java.util.Objects;

/**
 * Thrown by {@link MerchantGateway#discover} when the merchant's profile could not be fetched or read (section 19). The
 * message never contains a response body. Unchecked: {@code discover} returns a profile, not an outcome (8.5).
 *
 * <p>{@link #reason()} is a fixed code ({@code incompatible_version}, {@code capability_missing},
 * {@code endpoint_not_allowed}, {@code handler_missing}, {@code invalid_profile}, {@code unreachable}) that a caller
 * maps by constant, never by reading the message.
 */
public final class DiscoveryFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The reason of an exception built without one: the profile could not be had. */
    public static final String UNREACHABLE = "unreachable";

    private final String reason;

    public DiscoveryFailedException(String message) {
        this(message, UNREACHABLE);
    }

    public DiscoveryFailedException(String message, String reason) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public String reason() {
        return reason;
    }
}
