package dev.ucplab.core.merchant;

import java.util.Objects;
import java.util.Optional;

/**
 * The buyer fields of a checkout (2026-08-25 {@code buyer.json}: all optional). An update replaces the whole session,
 * so every field the merchant holds must be resent (8.4). Personal data: {@link #toString()} shows presence only.
 */
public record Buyer(
        Optional<String> email, Optional<String> firstName, Optional<String> lastName, Optional<String> phoneNumber) {

    public Buyer {
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(firstName, "firstName");
        Objects.requireNonNull(lastName, "lastName");
        Objects.requireNonNull(phoneNumber, "phoneNumber");
    }

    public static Buyer ofEmail(String email) {
        return new Buyer(Optional.of(Objects.requireNonNull(email, "email")), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    /** This buyer, with each field it lacks taken from {@code other}. */
    public Buyer withMissingFrom(Buyer other) {
        Objects.requireNonNull(other, "other");
        return new Buyer(email.or(() -> other.email), firstName.or(() -> other.firstName),
                lastName.or(() -> other.lastName), phoneNumber.or(() -> other.phoneNumber));
    }

    @Override
    public String toString() {
        return "Buyer[email=" + (email.isPresent() ? "set" : "none") + ", name="
                + (firstName.isPresent() || lastName.isPresent() ? "set" : "none") + ", phone="
                + (phoneNumber.isPresent() ? "set" : "none") + "]";
    }
}
