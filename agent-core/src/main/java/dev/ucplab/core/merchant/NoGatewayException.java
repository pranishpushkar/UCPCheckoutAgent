package dev.ucplab.core.merchant;

import java.util.Objects;

/**
 * No gateway is configured for a merchant an intent belongs to (I11, 19). Thrown by the orchestrator before it writes
 * anything or sends anything, so the intent stays exactly where it was; it never falls back to another merchant's
 * gateway. The message names the merchant id and nothing about the user.
 */
public final class NoGatewayException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient MerchantId merchant;

    public NoGatewayException(MerchantId merchant) {
        super("no gateway for merchant " + Objects.requireNonNull(merchant, "merchant"));
        this.merchant = merchant;
    }

    public MerchantId merchant() {
        return merchant;
    }
}
