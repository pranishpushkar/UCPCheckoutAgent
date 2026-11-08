package dev.ucplab.core.merchant;

import java.util.Objects;

/** Identifier of a merchant in the registry (section 19). */
public record MerchantId(String value) {

    public MerchantId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("MerchantId must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
