package dev.ucplab.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The lab console's three static files (24.1 to 24.3, m6-plan section 7, C18), read as text: there is no browser in
 * verify, so the rules a browser would never let a page break are checked in the source. Covers: 24.1, 24.3, I2, 9.3 L4
 * (the approval card shows merchant figures only, from the approval response only).
 */
class ConsoleStaticTest {

    private static final Path STATIC = Path.of("src/main/resources/static");

    private static String read(String name) throws IOException {
        return Files.readString(STATIC.resolve(name));
    }

    /** The code between {@code // <approval-card>} and {@code // </approval-card>} in console.js. */
    private static String card(String js) {
        int from = js.indexOf("// <approval-card>");
        int to = js.indexOf("// </approval-card>");
        assertThat(from).as("the card's start marker").isGreaterThanOrEqualTo(0);
        assertThat(to).as("the card's end marker").isGreaterThan(from);
        return js.substring(from, to);
    }

    // Covers: 24.3 (text uses textContent, never innerHTML: no way to write markup from data, and no dynamic code)
    @Test
    void javascript_neverWritesMarkup_orRunsDynamicCode() throws IOException {
        String js = read("console.js");

        for (String forbidden : List.of("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(",
                "new Function", "setTimeout(\"", "setInterval(\"", "createContextualFragment", "srcdoc")) {
            assertThat(js).as("console.js uses " + forbidden).doesNotContain(forbidden);
        }
        assertThat(js).contains("textContent");
    }

    // Covers: 24.3, 21 (no browser storage: the session is the cookie, and the approval token lives in a variable only)
    @Test
    void javascript_neverNamesBrowserStorage() throws IOException {
        String js = read("console.js");

        for (String storage : List.of("localStorage", "sessionStorage", "indexedDB", "document.cookie")) {
            assertThat(js).as("console.js uses " + storage).doesNotContain(storage);
        }
    }

    // Covers: 24.3, 7 CSP (the page has an aria-live region, no inline script and no inline handler or style, and links
    // only its own two files, so the CSP without 'unsafe-inline' holds)
    @Test
    void html_hasAnAriaLiveRegion_noInlineScript_noInlineHandler() throws IOException {
        String html = read("index.html");

        assertThat(html).contains("aria-live=\"polite\"");
        assertThat(html).contains("<meta name=\"viewport\"");
        Matcher scripts = Pattern.compile("<script\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        int count = 0;
        while (scripts.find()) {
            count++;
            assertThat(scripts.group()).contains("src=\"/console.js\"");
        }
        assertThat(count).isEqualTo(1);
        assertThat(html).doesNotContainPattern("(?i)\\son[a-z]+\\s*=").doesNotContainPattern("(?i)\\sstyle\\s*=")
                .doesNotContainPattern("(?i)<style\\b").doesNotContain("javascript:");
        assertThat(html).contains("href=\"/console.css\"");
        assertThat(html).doesNotContainPattern("(?i)(src|href)=\"https?:");
    }

    // Covers: 24.3 (native controls, visible focus, and a layout that collapses to one column for 360 px)
    @Test
    void css_hasFocusOutline_andACollapseBelow720() throws IOException {
        String css = read("console.css");

        assertThat(css).contains(":focus-visible").contains("max-width: 720px");
        assertThat(css).doesNotContain("outline: none").doesNotContain("outline:none");
        assertThat(css).doesNotContainPattern("(?i)url\\(\\s*['\"]?https?:").doesNotContain("@import");
    }

    // Covers: I2, 9.3 L4, 24.3 (the approval card is its own component and reads only the approval response's fields:
    // the snapshot's lines, currency and total, the expiry and the token; never the chat, the run or the messages)
    @Test
    void approvalCard_readsOnlyTheApprovalResponsesFields() throws IOException {
        String js = read("console.js");
        String card = card(js);

        Set<String> allowed = Set.of("snapshot", "lines", "item_id", "quantity", "unit_price_minor", "currency",
                "total_minor", "expires_at", "token", "merchant_id", "completion", "note");
        Set<String> read = new LinkedHashSet<>();
        Matcher fields = Pattern.compile("\\b(?:approval|snapshot|line)\\.([A-Za-z_]+)").matcher(card);
        while (fields.find()) {
            read.add(fields.group(1));
        }
        assertThat(read).as("fields the card reads").isNotEmpty().isSubsetOf(allowed);
        for (String other : List.of("messages", "chat", "tool_events", "run_id", "intent_ids", "/api/runs", "reason",
                "rationale", "title", "description", "model")) {
            assertThat(card).as("the card's code names " + other).doesNotContain(other);
        }
        assertThat(card).contains("/approval");
        // The one call site feeds it the approval endpoint's answer and nothing else.
        Matcher calls = Pattern.compile("showApprovalCard\\(([^)]*)\\)").matcher(js);
        List<String> arguments = new java.util.ArrayList<>();
        while (calls.find()) {
            arguments.add(calls.group(1).trim());
        }
        assertThat(arguments).as("calls of showApprovalCard (one definition, one use)")
                .containsExactly("id, approvalResponse", "id, approvalResponse");
        assertThat(card).contains("const approvalResponse = await api(\"GET\", \"/api/intents/\" + id + \"/approval\");");
    }

    // Covers: 9.3 L4, I2, I3, 24.2, 24.3, D7, m6a C9 (one card per awaiting intent: each is built for its own intent, headed by
    // the merchant of its own snapshot, and its Approve and Reject carry that card's own id and token, never a shared
    // button; the page has a container for the cards and no single card)
    @Test
    void approvalCards_areOnePerIntent_headedByTheirOwnMerchant_withTheirOwnToken() throws IOException {
        String js = read("console.js");
        String card = card(js);
        String html = read("index.html");

        assertThat(card).contains("snapshot.merchant_id").contains("data-intent");
        assertThat(card).contains("approve.onclick = () => decide(id, token, \"approve\");")
                .contains("reject.onclick = () => decide(id, token, \"reject\");");
        for (String shared : List.of("$(\"approve\")", "$(\"reject\")", "$(\"card-body\")", "$(\"card-foot\")",
                "$(\"approval-card\")")) {
            assertThat(card).as("the card's code uses the single-card element " + shared).doesNotContain(shared);
        }
        assertThat(html).contains("id=\"approval-cards\"").doesNotContain("id=\"approval-card\"")
                .doesNotContain("id=\"approve\"");
        // Every awaiting intent of a run is asked for its approval, not only the newest.
        assertThat(js).contains("intents.forEach(loadApproval)");
    }

    // Covers: 24.3, D7, 23.3, m6a C9 (the fault lab names a merchant: a selector filled from /api/lab/merchants, "Merchant
    // down" instead of "Merchant A down", faults sent with the chosen merchant_id, one order counter per merchant, and the
    // run's other intents as one line each on the timeline)
    @Test
    void faultLab_hasAMerchantSelector_andACounterPerMerchant_andTheRunsOtherIntents() throws IOException {
        String js = read("console.js");
        String html = read("index.html");

        assertThat(html).contains("id=\"lab-merchant\"").contains(">Merchant down<").doesNotContain("Merchant A down")
                .contains("id=\"order-counters\"").contains("id=\"other-intents\"").doesNotContain("id=\"orders\"");
        assertThat(js).contains("/api/lab/merchants").contains("merchant_id: $(\"lab-merchant\").value")
                .contains("/api/lab/faults?merchant_id=").contains("/api/lab/orders?merchant_id=")
                .doesNotContain("const MERCHANT = ").contains("showOtherIntents");
    }

    // Covers: 24.3 (the card's sentence about price and stock, the countdown, and the tax line computed from the snapshot)
    @Test
    void approvalCard_showsTheFixedSentence_theCountdown_andTheComputedCharges() throws IOException {
        String card = card(read("console.js"));

        assertThat(card).contains("Price and stock are not held").contains("expires_at").contains("Other charges (tax)");
    }

    // Covers: 23.2, 24.3, H5 C7c (a handoff merchant's card shows the answer's note, our fixed text, as text; only when
    // the answer's completion is handoff)
    @Test
    void approvalCard_showsTheHandoffNote_onlyAtAHandoffMerchant() throws IOException {
        String card = card(read("console.js"));

        assertThat(card).contains("if (approval.completion === \"handoff\") {")
                .contains("card.appendChild(el(\"p\", approval.note, \"fixed\"));");
    }

    /** The code between {@code // <handoff-link>} and {@code // </handoff-link>} in console.js. */
    private static String handoffLink(String js) {
        int from = js.indexOf("// <handoff-link>");
        int to = js.indexOf("// </handoff-link>");
        assertThat(from).as("the link's start marker").isGreaterThanOrEqualTo(0);
        assertThat(to).as("the link's end marker").isGreaterThan(from);
        return js.substring(from, to);
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) {
            n++;
        }
        return n;
    }

    // Covers: 24.3, 23.2, I9, H5 (the Continue at the store link: built only in HANDED_OFF, from the handoff_url of the
    // owner's GET /api/intents/{id}, read once and nowhere else; removed when another intent is watched; a link in a new
    // tab with rel="noopener noreferrer" and referrerpolicy="no-referrer")
    @Test
    void handoffLink_existsOnlyInHandedOff_fromTheIntentsHandoffUrl() throws IOException {
        String js = read("console.js");
        String link = handoffLink(js);

        assertThat(count(js, "handoff_url")).as("handoff_url is read once").isEqualTo(1);
        assertThat(js).contains("    if (state === \"HANDED_OFF\") {\n      showHandoffLink(result.data.handoff_url);\n"
                + "    } else {\n      clear($(\"handoff\"));\n    }\n");
        assertThat(count(js, "showHandoffLink(")).as("one definition, one call").isEqualTo(2);
        int watch = js.indexOf("function watchIntent(id) {");
        assertThat(js.indexOf("clear($(\"handoff\"));", watch)).as("watching another intent drops the link")
                .isBetween(watch, js.indexOf("pollIntent();", watch));
        assertThat(link).contains("const anchor = el(\"a\", \"Continue at the store\");")
                .contains("anchor.href = url;").contains("anchor.target = \"_blank\";")
                .contains("anchor.rel = \"noopener noreferrer\";")
                .contains("anchor.referrerPolicy = \"no-referrer\";");
    }

    /** The code between {@code // <follow-up>} and {@code // </follow-up>} in console.js. */
    private static String followUp(String js) {
        int from = js.indexOf("// <follow-up>");
        int to = js.indexOf("// </follow-up>");
        assertThat(from).as("the follow-up block").isNotNegative();
        assertThat(to).isGreaterThan(from);
        return js.substring(from, to);
    }

    // Covers: 24.3, 7.6, VERIFY 11 (in HANDED_OFF the timeline shows what the store reported: "Order placed at the store,
    // confirmation #<label>" from store_order.label as text, or waiting, or no order within 24 h; never "paid": a
    // completed checkout means an order was placed, not that it was paid)
    @Test
    void followUp_showsTheStoresOrder_asText_andNeverSaysPaid() throws IOException {
        String js = read("console.js");
        String block = followUp(js);

        assertThat(block).contains("function showFollowUp(order, followUp) {")
                .contains("\"Order placed at the store, confirmation #\" + order.label")
                .contains("\"Waiting for the store to report an order\"")
                .contains("\"The store reported no order within 24 h\"")
                .contains("holder.appendChild(el(\"p\", text));");
        for (String forbidden : List.of("innerHTML", "insertAdjacentHTML", "permalink", "console.", "Storage")) {
            assertThat(block).as("the follow-up's code uses " + forbidden).doesNotContain(forbidden);
        }
        assertThat(js.toLowerCase(java.util.Locale.ROOT)).as("the console never says paid").doesNotContain("paid");
        assertThat(read("index.html")).contains("<p id=\"follow-up\" aria-live=\"polite\"></p>");
    }

    // Covers: 24.3, 7.6 (in HANDED_OFF the follow-up and the link are shown on every poll, not only when the state
    // changes; once the store's order is shown the link goes, and it stays when the follow-up ended without one)
    @Test
    void followUp_isShownOnEveryPoll_andTheLinkGoesOnlyOnceAnOrderIsShown() throws IOException {
        String js = read("console.js");

        assertThat(js).contains("  if (state === \"HANDED_OFF\") {\n"
                + "    showFollowUp(result.data.store_order, result.data.follow_up);\n"
                + "    if (result.data.store_order) {\n      clear($(\"handoff\"));\n    } else {\n"
                + "      showHandoffLink(result.data.handoff_url);\n    }\n  }\n");
        int watch = js.indexOf("function watchIntent(id) {");
        assertThat(js.indexOf("clear($(\"follow-up\"));", watch)).as("watching another intent drops the follow-up")
                .isBetween(watch, js.indexOf("pollIntent();", watch));
    }

    // Covers: 24.3, 7.6 (the timeline stops polling a terminal intent, except a HANDED_OFF one while its follow-up is
    // watching: then every 10 s)
    @Test
    void timeline_pollsAHandedOffIntent_every10s_whileItsFollowUpIsWatching() throws IOException {
        String js = read("console.js");

        assertThat(js).contains("  if (!TERMINAL.includes(state)) {\n    intentTimer = window.setTimeout(pollIntent, 1000);\n"
                + "  } else if (state === \"HANDED_OFF\" && result.data.follow_up && result.data.follow_up.watching) {\n"
                + "    intentTimer = window.setTimeout(pollIntent, 10000);\n  }\n");
    }

    // Covers: 24.3, I8, H5 (the handoff URL is never page text: inside the link's code it is only checked and set as the
    // href; the code writes no text from it, no status, no console line, no location, history or storage)
    @Test
    void handoffLink_neverShowsTheUrlAsText() throws IOException {
        String link = handoffLink(read("console.js"));

        assertThat(count(link, "url")).as("url: the parameter, the https check and the href only").isEqualTo(4);
        for (String forbidden : List.of("textContent", "innerText", "setStatus", "console.", "location", "history",
                "Storage", "title", "aria-", "setAttribute", "alert(", "prompt(")) {
            assertThat(link).as("the link's code uses " + forbidden).doesNotContain(forbidden);
        }
        assertThat(read("console.js")).as("no console line anywhere").doesNotContain("console.");
    }

    // Covers: 24.3, I8, H5 (the link's holder is outside the aria-live region, which stays an empty container)
    @Test
    void handoffLink_isOutsideTheAriaLiveRegion() throws IOException {
        String html = read("index.html");

        assertThat(html).contains("<div id=\"status\" class=\"status\" role=\"status\" aria-live=\"polite\"></div>")
                .contains("<p id=\"handoff\"></p>");
        assertThat(count(html, "aria-live")).as("one live region").isEqualTo(1);
    }

    // Covers: 7.1, H5 C7c (HANDED_OFF is terminal: the timeline stops polling a handed-off intent)
    @Test
    void timeline_stopsAtHandedOff() throws IOException {
        assertThat(read("console.js"))
                .contains("const TERMINAL = [\"COMPLETED\", \"HANDED_OFF\", \"REJECTED\", \"FAILED_NOT_CHARGED\", \"ESCALATED\"];");
    }

    // Covers: 24.3 (a 401 goes to /auth/login, reauth_required goes to /auth/login?reauth=true&return_to=/intents/{id})
    @Test
    void javascript_signsInOn401_andReauthsOnReauthRequired() throws IOException {
        String js = read("console.js");

        assertThat(js).contains("/auth/login").contains("reauth=true&return_to=/intents/").contains("reauth_required")
                .contains("/api/me").contains("X-CSRF-Token").contains("setStatus");
    }

    // Covers: 24.3, 18.6 (as amended after M6: back from a re-authentication the console shows the card and waits; an
    // approval is only ever sent by the person pressing Approve or Reject, never by the page on its own)
    @Test
    void decide_isCalledOnlyFromTheApproveAndRejectButtons() throws IOException {
        List<String> calls = read("console.js").lines()
                .filter(l -> l.contains("decide(") && !l.contains("async function decide(")).toList();

        assertThat(calls).hasSize(2).allMatch(l -> l.matches("\\s*(approve|reject)\\.onclick = \\(\\) => "
                + "decide\\(id, token, \"(approve|reject)\"\\);"));
    }

    // Covers: 24.3, T6 (another user's intent, or a missing one, says "not found" instead of leaving an empty panel)
    @Test
    void timeline_saysNotFound_forAnIntentThatIsNotTheCallers() throws IOException {
        String js = read("console.js");

        assertThat(js).contains("result.status === 404").contains("Intent not found.");
    }

    // Covers: 24.3, P7 (the timeline names a re-quote after price_changed, and says who decided the approval)
    @Test
    void timeline_namesThePriceChange_andWhoDecided() throws IOException {
        String js = read("console.js");

        assertThat(js).contains("PRICE_CHANGED").contains("Price changed: re-quoting")
                .contains("Approved by you").contains("Rejected by you")
                .doesNotContain("\"Approval \" + (p.decision");
    }
}
