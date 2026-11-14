package dev.ucplab.core.merchant;

import dev.ucplab.core.intent.ProposedLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Full replacement of a session's buyer and line items (A.1, 8.4). Appendix B names this type without fields. Each
 * line carries the merchant's line id when the last view had one, and the buyer carries every field the merchant held.
 */
public record UpdateCheckout(List<UpdateLine> lines, Optional<Buyer> buyer, Optional<FulfillmentSelection> fulfillment) {

    /** An update with no fulfillment part: the lab merchant, and every update before a merchant asks for shipping. */
    public UpdateCheckout(List<UpdateLine> lines, Optional<Buyer> buyer) {
        this(lines, buyer, Optional.empty());
    }

    public UpdateCheckout {
        Objects.requireNonNull(fulfillment, "fulfillment");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        Objects.requireNonNull(buyer, "buyer");
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("an update needs at least one line");
        }
    }

    /** An update with no line ids and an email-only buyer: what a first update without an earlier view can send. */
    public static UpdateCheckout of(List<ProposedLine> lines, Optional<String> buyerEmail) {
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(buyerEmail, "buyerEmail");
        return new UpdateCheckout(
                lines.stream().map(l -> new UpdateLine(l.itemId(), l.quantity(), Optional.empty())).toList(),
                buyerEmail.map(Buyer::ofEmail));
    }

    /**
     * The update for the proposed {@code lines}: each takes the id of the first unused line of {@code last} with the
     * same item, and the buyer is {@code buyer} with any field it lacks taken from the last view's buyer.
     */
    public static UpdateCheckout from(List<ProposedLine> lines, Buyer buyer, Optional<QuoteShape> last) {
        Objects.requireNonNull(lines, "lines");
        Objects.requireNonNull(buyer, "buyer");
        Objects.requireNonNull(last, "last");
        List<LineView> available = new ArrayList<>(last.map(QuoteShape::lines).orElse(List.of()));
        List<UpdateLine> out = new ArrayList<>();
        for (ProposedLine line : lines) {
            Optional<String> id = Optional.empty();
            for (int i = 0; i < available.size(); i++) {
                if (available.get(i).itemId().equals(line.itemId()) && available.get(i).lineId().isPresent()) {
                    id = available.remove(i).lineId();
                    break;
                }
            }
            out.add(new UpdateLine(line.itemId(), line.quantity(), id));
        }
        Buyer merged = last.flatMap(QuoteShape::buyer).map(buyer::withMissingFrom).orElse(buyer);
        return new UpdateCheckout(out, Optional.of(merged));
    }

    /** This update with {@code selection} as its fulfillment part. */
    public UpdateCheckout withFulfillment(Optional<FulfillmentSelection> selection) {
        return new UpdateCheckout(lines, buyer, selection);
    }
}
