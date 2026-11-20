package dev.ucplab.core.merchant;

import java.util.Objects;

/**
 * One of a variant's own options, such as {@code Size} {@code 149 cm} (6.2, 26.7): the merchant's text, untrusted (6.4).
 * It names which variant an item id buys.
 */
public record VariantOption(String name, String label) {
    public VariantOption {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(label, "label");
        if (name.isBlank() || label.isBlank()) {
            throw new IllegalArgumentException("an option has a name and a label");
        }
    }
}
