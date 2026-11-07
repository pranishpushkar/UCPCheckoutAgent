package dev.ucplab.core.merchant;

import dev.ucplab.core.intent.ProposedLine;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Full replacement of a session's buyer and line items (A.1). Appendix B names this type without fields. */
public record UpdateCheckout(List<ProposedLine> lines, Optional<String> buyerEmail) {

    public UpdateCheckout {
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        Objects.requireNonNull(buyerEmail, "buyerEmail");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("an update needs at least one line");
        }
    }
}
