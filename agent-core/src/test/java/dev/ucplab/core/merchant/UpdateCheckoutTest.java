package dev.ucplab.core.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.ProposedLine;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UpdateCheckoutTest {

    private static final QuoteShape LAST = new QuoteShape(
            List.of(new LineView("item_123", 2, 2500, Optional.of("li_1")),
                    new LineView("item_456", 1, 1200, Optional.of("li_2")),
                    new LineView("item_123", 1, 2500, Optional.of("li_3"))),
            Optional.of(new Buyer(Optional.of("old@example.com"), Optional.of("Ada"), Optional.of("Lovelace"),
                    Optional.empty())));

    // Covers: decision L (each proposed line takes the id of the first unused line of the last view with the same item;
    // a new item has no id; the same item twice takes two ids in order)
    @Test
    void from_matchesLineIdsByItemInOrder() {
        UpdateCheckout u = UpdateCheckout.from(
                List.of(new ProposedLine("item_123", 2), new ProposedLine("item_789", 1), new ProposedLine("item_123", 1)),
                Buyer.ofEmail("a@example.com"), Optional.of(LAST));

        assertThat(u.lines()).containsExactly(new UpdateLine("item_123", 2, Optional.of("li_1")),
                new UpdateLine("item_789", 1, Optional.empty()), new UpdateLine("item_123", 1, Optional.of("li_3")));
    }

    // Covers: decision L (the buyer's email is ours; the other buyer fields the merchant holds are resent, so the
    // replacement does not wipe them)
    @Test
    void from_resendsBuyerFieldsOfTheLastView_withOurEmail() {
        UpdateCheckout u = UpdateCheckout.from(List.of(new ProposedLine("item_123", 1)), Buyer.ofEmail("a@example.com"),
                Optional.of(LAST));

        assertThat(u.buyer()).contains(new Buyer(Optional.of("a@example.com"), Optional.of("Ada"),
                Optional.of("Lovelace"), Optional.empty()));
    }

    // Covers: decision L (with no earlier view there are no ids and the buyer is just ours)
    @Test
    void from_withoutLastView_hasNoIdsAndOnlyOurBuyer() {
        UpdateCheckout u = UpdateCheckout.from(List.of(new ProposedLine("item_123", 1)), Buyer.ofEmail("a@example.com"),
                Optional.empty());

        assertThat(u.lines()).containsExactly(new UpdateLine("item_123", 1, Optional.empty()));
        assertThat(u.buyer()).contains(Buyer.ofEmail("a@example.com"));
    }
}
