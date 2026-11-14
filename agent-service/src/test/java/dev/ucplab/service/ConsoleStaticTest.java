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
                "total_minor", "expires_at", "token");
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

    // Covers: 24.3 (the card's sentence about price and stock, the countdown, and the tax line computed from the snapshot)
    @Test
    void approvalCard_showsTheFixedSentence_theCountdown_andTheComputedCharges() throws IOException {
        String card = card(read("console.js"));

        assertThat(card).contains("Price and stock are not held").contains("expires_at").contains("Other charges (tax)");
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

        assertThat(calls).hasSize(2).allMatch(l -> l.matches("\\s*\\$\\(\"(approve|reject)\"\\)\\.onclick = \\(\\) => "
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
