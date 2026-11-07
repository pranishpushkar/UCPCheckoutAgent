package dev.ucplab.core.merchant;

import java.util.Objects;

/**
 * One line of a merchant checkout as the merchant reported it. Appendix B names this type without
 * fields; these are the fields an approval snapshot line needs (9.2).
 */
public record LineView(String itemId, int quantity, long unitPriceMinor) {

    public LineView {
        Objects.requireNonNull(itemId, "itemId");
    }
}
