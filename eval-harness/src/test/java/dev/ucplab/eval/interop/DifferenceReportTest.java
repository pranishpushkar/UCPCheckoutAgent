package dev.ucplab.eval.interop;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.ucp.HttpCall;
import dev.ucplab.ucp.HttpReply;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The wire diff and the report's rules on canned recordings (H1 C9b): no merchant, no Python. */
class DifferenceReportTest {

    private static final String TABLE = "| id | class | match | evidence | why | status |\n| --- | --- | --- | --- | --- | --- |\n"
            + "| status-family | choice | */complete/status:* | computed | both legal | n/a |\n"
            + "| envelope | theirs | */field-only-sample:resp:ucp.* | computed | their envelope | n/a |\n"
            + "| old-bug | ours | | history | a bug | fixed in abc123 |\n"
            + "| open-bug | ours | | history | a bug | open |\n";

    private static WireStep step(String name, int status, Map<String, String> resp) {
        return new WireStep(name, "/checkout-sessions/{id}/complete", status, Map.of(), resp, "");
    }

    // Covers: R1, 13.5 (a new field in the sample's canned complete response, one no entry matches, comes out as UNCLASSIFIED;
    // the hand-written set of C9 could never see it)
    @Test
    void aPlantedNewField_isUnclassified() {
        var entries = DifferenceReport.parse(TABLE);
        var lab = List.of(step("complete", 200, Map.of("status", "string", "order", "object")));
        var sample = List.of(step("complete", 200, Map.of("status", "string", "order", "object", "ucp.version", "string",
                "brand_new_field", "string")));

        var diffs = WireDiff.diff("S0", lab, sample);
        var report = DifferenceReport.evaluate(diffs, entries);

        assertThat(diffs).extracting(WireDiff.Diff::matchText).contains("S0/complete/field-only-sample:resp:brand_new_field");
        assertThat(report.violations()).filteredOn(v -> v.contains("UNCLASSIFIED")).singleElement().asString().contains("brand_new_field");
        assertThat(report.explained()).containsKey("S0/complete/field-only-sample:resp:ucp.version");
    }

    // Covers: R1 (a changed HTTP status is a difference, and its id does not contain the values)
    @Test
    void aChangedStatus_isADifference() {
        var diffs = WireDiff.diff("S6", List.of(step("complete", 200, Map.of())), List.of(step("complete", 409, Map.of())));

        assertThat(diffs).singleElement().satisfies(d -> {
            assertThat(d.kind()).isEqualTo("status");
            assertThat(d.detail()).isEqualTo("200|409");
            assertThat(d.id()).isEqualTo("S6/complete/status").doesNotContain("409");
        });
    }

    // Covers: R1 (a step on one side only, a type change and an error code change are differences)
    @Test
    void stepsTypesAndErrorCodes_areDifferences() {
        var lab = List.of(new WireStep("complete", "/p", 200, Map.of(), Map.of("x", "string"), "out_of_stock"));
        var sample = List.of(new WireStep("complete", "/p", 200, Map.of(), Map.of("x", "number"), "OUT_OF_STOCK"),
                new WireStep("update", "/p", 200, Map.of(), Map.of(), ""));

        assertThat(WireDiff.diff("S6", lab, sample)).extracting(WireDiff.Diff::kind)
                .containsExactlyInAnyOrder("error-code", "type", "step-only-sample");
    }

    // Covers: R1, 13.5 (an entry that matches no wire difference fails, unless it is evidence: probe or history; an ours
    // without a fix or reason fails)
    @Test
    void anEntryThatMatchesNothing_fails_andAnOpenOursFails() {
        var entries = DifferenceReport.parse(TABLE);

        var report = DifferenceReport.evaluate(List.of(), entries);

        assertThat(report.violations()).anySatisfy(v -> assertThat(v).contains("matches nothing").contains("status-family"))
                .anySatisfy(v -> assertThat(v).contains("matches nothing").contains("envelope"))
                .anySatisfy(v -> assertThat(v).contains("OURS").contains("open-bug"))
                .noneSatisfy(v -> assertThat(v).contains("old-bug"));
    }

    // Covers: 13.5 (two runs of the same merchant, with other ids and other values, produce no difference after normalisation)
    @Test
    void twoRunsOfTheSameMerchant_produceNoDifference() throws Exception {
        List<WireStep> one = record("11111111-1111-1111-1111-111111111111", "tok_a", 111);
        List<WireStep> two = record("22222222-2222-2222-2222-222222222222", "tok_b", 222);

        assertThat(WireDiff.diff("S0", one, two)).isEmpty();
        assertThat(one.get(0).path()).isEqualTo("/checkout-sessions/{id}/complete");
    }

    // Covers: I8 (a payment token, an Authorization header and an address in a call never reach a recording: only paths and
    // types are kept)
    @Test
    void theCanaryToken_neverAppearsInARecording() throws Exception {
        String canary = "tok_canary_c9b_7f3";
        List<WireStep> steps = record("33333333-3333-3333-3333-333333333333", canary, 1);

        assertThat(steps.toString()).doesNotContain(canary).doesNotContain("Bearer");
    }

    private static List<WireStep> record(String id, String token, int total) throws IOException {
        RecordingTransport t = new RecordingTransport(call -> new HttpReply(200, Map.of(),
                ("{\"id\":\"" + id + "\",\"status\":\"completed\",\"total\":" + total + ",\"echo\":\"" + token + "\"}")
                        .getBytes(StandardCharsets.UTF_8)));
        var body = Optional.of(dev.ucplab.core.merchant.CheckoutWire.completeBody(PaymentInstrument.sampleCard("pi", token)));
        t.send(new HttpCall("POST", URI.create("http://127.0.0.1:1/checkout-sessions/" + id + "/complete"),
                Map.of("Authorization", "Bearer " + token), body, Duration.ofSeconds(1)));
        return t.steps();
    }
}
