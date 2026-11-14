package dev.ucplab.core.merchant;

import java.util.Objects;

/**
 * No payment instrument is configured for a merchant an intent belongs to (I11, 19). Thrown by the orchestrator before it
 * writes anything or sends anything, like {@link NoGatewayException}: the intent stays where it was, and it never falls
 * back to another merchant's instrument (a token for one merchant's handler must not reach another). The message names
 * the merchant id and nothing else.
 */
public final class NoInstrumentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient MerchantId merchant;

    public NoInstrumentException(MerchantId merchant) {
        super("no payment instrument for merchant " + Objects.requireNonNull(merchant, "merchant"));
        this.merchant = merchant;
    }

    public MerchantId merchant() {
        return merchant;
    }
}
