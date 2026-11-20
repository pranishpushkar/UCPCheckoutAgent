package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.BuyerCountry;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.merchant.MerchantId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OrchestratorBuyerCountryTest {

    private static final BuyerCountry INDIA = new BuyerCountry("IN");

    private final Lab lab = new Lab();
    private final Lab.Worker w = lab.worker("worker-a");

    // Covers: 7.7, 23.1 (for US the dedupe key is the key before the buyer country; any other country is part of it)
    @Test
    void theDedupeKey_isUnchangedForUS_andCoversAnyOtherCountry() {
        UserId alice = new UserId("alice");
        MerchantId shop = new MerchantId("shop");
        String before = DefaultOrchestrator.dedupeKey(alice, shop, TWO_SHIRTS, Optional.empty());

        assertThat(DefaultOrchestrator.dedupeKey(alice, shop, TWO_SHIRTS, Optional.empty(), BuyerCountry.US))
                .isEqualTo(before);
        String india = DefaultOrchestrator.dedupeKey(alice, shop, TWO_SHIRTS, Optional.empty(), INDIA);
        assertThat(india).isNotEqualTo(before)
                .isNotEqualTo(DefaultOrchestrator.dedupeKey(alice, shop, TWO_SHIRTS, Optional.empty(),
                        new BuyerCountry("GB")))
                .isEqualTo(DefaultOrchestrator.dedupeKey(alice, shop, TWO_SHIRTS, Optional.empty(), INDIA));
    }

    // Covers: 7.7, 23.1, 10 (V13) (the intent keeps the run's buyer country; an identical proposal for the same country
    // returns the active intent, and for another country is a new proposal)
    @Test
    void theIntent_keepsTheRunsCountry_andAnIdenticalProposalIsReusedOnlyForTheSameCountry() {
        ProposalResult first = w.orchestrator().propose(lab.alice(), Lab.SHOP, TWO_SHIRTS, "two red t-shirts",
                Optional.empty(), INDIA);
        ProposalResult same = w.orchestrator().propose(lab.alice(), Lab.SHOP, TWO_SHIRTS, "two red t-shirts",
                Optional.empty(), INDIA);
        ProposalResult us = w.orchestrator().propose(lab.alice(), Lab.SHOP, TWO_SHIRTS, "two red t-shirts",
                Optional.empty(), BuyerCountry.US);

        assertThat(lab.intent(first.intentId()).shipTo()).isEqualTo(INDIA);
        assertThat(same.intentId()).isEqualTo(first.intentId());
        assertThat(us.intentId()).isNotEqualTo(first.intentId());
        assertThat(lab.intent(us.intentId()).shipTo()).isEqualTo(BuyerCountry.US);
    }
}
