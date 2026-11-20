package dev.ucplab.service.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.approval.ApprovalSnapshot;
import dev.ucplab.core.approval.SnapshotLine;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.VariantOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The quote as the approval answer shows it (23.2, 9.2): built from the stored snapshot only. */
class QuoteCardTest {

    private static ApprovalSnapshot snapshot(List<VariantOption> options) {
        return new ApprovalSnapshot(new IntentId("i-1"), new MerchantId("shopify-catalog"),
                new SessionId("link:gid://shopify/ProductVariant/1000001"),
                List.of(new SnapshotLine("gid://shopify/ProductVariant/1000001", 1, 22498, "Arbor Element Decon 2026",
                        options)),
                "USD", 22498, 22498, Instant.parse("2026-01-01T00:10:00Z"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> line(ApprovalSnapshot snapshot) {
        return ((List<Map<String, Object>>) QuoteCard.of(snapshot).get("lines")).getFirst();
    }

    // Covers: 9.2, 23.2, 24.3, I2 (a line's options go to the card from the snapshot, as [{name, label}] in their order;
    // a line without options has no options field)
    @Test
    void aLinesOptions_comeFromTheSnapshot_andAreAbsentWhenThereAreNone() {
        assertThat(line(snapshot(List.of(new VariantOption("Size", "154 cm"))))).containsEntry("options",
                List.of(Map.of("name", "Size", "label", "154 cm")));
        assertThat(line(snapshot(List.of()))).doesNotContainKey("options");
    }
}
