package dev.ucplab.service.web;

import dev.ucplab.core.approval.ApprovalSnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The quote as the API shows it: {@code {merchant_id, currency, total_minor, lines[{item_id, quantity, unit_price_minor}]}}, built
 * only from an approval's {@link ApprovalSnapshot}, which the approval service made from the merchant's own quote (9.2).
 * No second parser of the merchant's response, and nothing from the model.
 */
final class QuoteCard {

    private QuoteCard() {
    }

    static Map<String, Object> of(ApprovalSnapshot snapshot) {
        Map<String, Object> card = new LinkedHashMap<>();
        // The merchant the quote is from, as the stored snapshot says (I3, I11): the card's heading is never the agent's words.
        card.put("merchant_id", snapshot.merchantId().value());
        card.put("currency", snapshot.currency());
        card.put("total_minor", snapshot.totalMinor());
        List<Map<String, Object>> lines = snapshot.lines().stream().map(l -> {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("item_id", l.itemId());
            line.put("quantity", l.quantity());
            line.put("unit_price_minor", l.unitPriceMinor());
            return line;
        }).toList();
        card.put("lines", lines);
        return card;
    }
}
