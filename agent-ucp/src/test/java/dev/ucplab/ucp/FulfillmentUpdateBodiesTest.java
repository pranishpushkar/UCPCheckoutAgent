package dev.ucplab.ucp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.merchant.Buyer;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CheckoutWire;
import dev.ucplab.core.merchant.FulfillmentPolicy;
import dev.ucplab.core.merchant.FulfillmentSelection;
import dev.ucplab.core.merchant.FulfillmentSteps;
import dev.ucplab.core.merchant.QuoteShape;
import dev.ucplab.core.merchant.UpdateCheckout;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Each selection update is built from the previous response only (7.2, 8.4, H1 C6): fed the recorded responses 04, 05 and 06
 * of the sample merchant (through the stored summary, as the orchestrator reads them), the bodies equal the requests the
 * sample accepted in fixtures 05, 06 and 07, byte for byte.
 */
class FulfillmentUpdateBodiesTest {

    private static final List<ProposedLine> LINES = List.of(new ProposedLine("bouquet_roses", 1));

    private static CheckoutView view(String fixture) {
        return ((CheckoutParser.Parsed.Checkout) CheckoutParser.parseCheckout(InteropFixtures.reply(fixture).body())).view();
    }

    @SuppressWarnings("unchecked")
    private static String recordedRequestBody(String fixture) {
        Map<String, Object> request = (Map<String, Object>) InteropFixtures.read(fixture).get("request");
        return CanonicalJson.write(request.get("body"));
    }

    /** What the orchestrator builds for the step after {@code previous}: from its stored summary and the policy. */
    private static String bodyAfter(String previousFixture, FulfillmentPolicy policy) {
        String summary = CheckoutWire.responseSummary(view(previousFixture));
        QuoteShape shape = CheckoutWire.quoteShape(summary).orElseThrow();
        List<String> lineIds = shape.lines().stream().flatMap(l -> l.lineId().stream()).toList();
        UpdateCheckout update = UpdateCheckout.from(LINES, Buyer.ofEmail("john.doe@example.com"), Optional.of(shape))
                .withFulfillment(FulfillmentSteps.next(shape.fulfillment(), lineIds, policy));
        return CheckoutWire.updateBody(update);
    }

    // Covers: 8.4, 7.2, H1 C6 (fixture 05: from the incomplete create the first update asks for the shipping method over
    // the line ids the create returned; the body is the one the sample accepted)
    @Test
    void theFirstSelection_isBuiltFromTheCreateResponse() {
        assertThat(bodyAfter("04-happy-create", FulfillmentPolicy.cheapest())).isEqualTo(recordedRequestBody("05-happy-fulfillment-1-methods"));
    }

    // Covers: 8.4, 7.2, H1 C6 (fixture 06: from the response that lists the destinations, the next update selects the first
    // one the merchant offered, by id)
    @Test
    void theSecondSelection_isBuiltFromTheMethodsResponse() {
        assertThat(bodyAfter("05-happy-fulfillment-1-methods", FulfillmentPolicy.cheapest()))
                .isEqualTo(recordedRequestBody("06-happy-fulfillment-2-destination"));
    }

    // Covers: 8.4, 7.2, H1 C6 (fixture 07: from the response that lists the options, the next update selects the cheapest
    // option with the group id the response showed)
    @Test
    void theThirdSelection_isBuiltFromTheDestinationResponse() {
        assertThat(bodyAfter("06-happy-fulfillment-2-destination", FulfillmentPolicy.cheapest()))
                .isEqualTo(recordedRequestBody("07-happy-fulfillment-3-option"));
    }

    // Covers: 7.2, H1 C6 (nothing is left to select once the option is chosen; a re-quote then repeats the selection)
    @Test
    void afterTheOption_nothingIsLeft_andAReQuoteRepeatsTheSelection() {
        QuoteShape shape = CheckoutWire.quoteShape(CheckoutWire.responseSummary(view("07-happy-fulfillment-3-option"))).orElseThrow();

        assertThat(FulfillmentSteps.next(shape.fulfillment(), List.of("x"), FulfillmentPolicy.cheapest())).isEmpty();
        Optional<FulfillmentSelection> carried = FulfillmentSteps.carry(shape.fulfillment());
        String requote = CheckoutWire.updateBody(UpdateCheckout.from(LINES, Buyer.ofEmail("john.doe@example.com"), Optional.of(shape))
                .withFulfillment(carried));
        assertThat(requote).isEqualTo(recordedRequestBody("07-happy-fulfillment-3-option"));
    }

    // Covers: 7.2, I6 (the policy is the cheapest option and the first destination; equal prices take the first)
    @Test
    void theDefaultPolicy_takesTheCheapestOption_andTheFirstDestination() {
        FulfillmentPolicy p = FulfillmentPolicy.cheapest();
        QuoteShape shape = CheckoutWire.quoteShape(CheckoutWire.responseSummary(view("06-happy-fulfillment-2-destination"))).orElseThrow();
        var options = shape.fulfillment().orElseThrow().methods().get(0).groups().get(0).options();

        assertThat(p.option(options)).contains("std-ship");
        assertThat(p.option(List.of(options.get(1), options.get(0)))).contains("std-ship");
        assertThat(p.destination(shape.fulfillment().orElseThrow().methods().get(0).destinations())).contains("addr_1");
        assertThat(p.option(List.of())).isEmpty();
    }
}
