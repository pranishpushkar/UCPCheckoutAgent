package dev.ucplab.core.merchant;

import java.util.Objects;
import java.util.Optional;

/**
 * Whether a variant can be obtained, as the merchant states it. {@code status} is the merchant's own word
 * ({@code in_stock}, {@code backorder}, {@code out_of_stock} and so on) and is untrusted text. A merchant that states
 * nothing is {@link #unstated()}, which is not available: the agent never proposes what the merchant did not say it can
 * supply. The quote at checkout stays authoritative (section 20).
 */
public record Availability(boolean available, Optional<String> status) {

    public Availability {
        Objects.requireNonNull(status, "status");
    }

    public static Availability unstated() {
        return new Availability(false, Optional.empty());
    }
}
