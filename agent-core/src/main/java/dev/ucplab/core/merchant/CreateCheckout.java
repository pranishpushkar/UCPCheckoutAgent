package dev.ucplab.core.merchant;

import dev.ucplab.core.intent.ProposedLine;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Request to create a checkout session. Appendix B names this type without fields. */
public record CreateCheckout(List<ProposedLine> lines, Optional<String> buyerEmail) {

    public CreateCheckout {
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        Objects.requireNonNull(buyerEmail, "buyerEmail");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("a checkout needs at least one line");
        }
    }
}
