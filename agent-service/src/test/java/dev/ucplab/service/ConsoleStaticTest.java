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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    /** The code between {@code // <name>} and {@code // </name>} in console.js. */
    private static String block(String js, String name) {
        int from = js.indexOf("// <" + name + ">");
        int to = js.indexOf("// </" + name + ">");
        assertThat(from).as("the start of " + name).isNotNegative();
        assertThat(to).as("the end of " + name).isGreaterThan(from);
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

    // Covers: 24.3, 21 (the one value in browser storage is the theme, under localStorage key ucplab.theme: the session
    // is the cookie, the approval token lives in a variable only, and nothing else is stored)
    @Test
    void javascript_storesOnlyTheTheme() throws IOException {
        String js = read("console.js");
        String theme = block(js, "theme");

        for (String storage : List.of("sessionStorage", "indexedDB", "document.cookie")) {
            assertThat(js).as("console.js uses " + storage).doesNotContain(storage);
        }
        String outside = js.replace(theme, "");
        assertThat(outside).as("localStorage outside the theme block").doesNotContain("localStorage");
        assertThat(theme.split("localStorage", -1)).as("one read and one write").hasSize(3);
        assertThat(theme).contains("localStorage.getItem(THEME_KEY)").contains("localStorage.setItem(THEME_KEY, ")
                .contains("const THEME_KEY = \"ucplab.theme\";");
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

    // Covers: 24.3 (light by default with the restyle's colours whatever the system setting: no prefers-color-scheme
    // anywhere; a dark variant of the same tokens only under data-theme="dark"; system fonts only with no font file, and
    // controls at least 44 px tall)
    @Test
    void css_isLightByDefault_withADarkVariant_systemFonts_and44pxControls() throws IOException {
        String css = read("console.css");
        assertThat(css).doesNotContain("prefers-color-scheme");
        assertThat(read("console.js")).doesNotContain("prefers-color-scheme").doesNotContain("matchMedia");
        int dark = css.indexOf(":root[data-theme=\"dark\"] {");
        String light = css.substring(0, dark);

        assertThat(dark).as("a dark variant").isPositive();
        for (String token : List.of("--bg: #ffffff;", "--surface: #ffffff;", "--border: #e3e3e3;", "--fg: #1a1a1a;",
                "--muted: #616161;", "--accent: #1f5ac9;", "--success: #1a7f37;")) {
            assertThat(light).as("the light default sets " + token).contains(token);
        }
        String darkBlock = css.substring(dark, css.indexOf("\n}\n", dark));
        assertThat(light).doesNotContain("data-theme");
        for (String token : List.of("--bg:", "--surface:", "--border:", "--fg:", "--muted:", "--accent:", "--success:",
                "--live-bg:", "--live-fg:")) {
            assertThat(darkBlock).as("the dark variant sets " + token).contains(token);
        }
        assertThat(css).contains("border-radius: 12px").contains("min-height: 44px")
                .contains("font: 16px/1.45 system-ui, -apple-system, \"Segoe UI\", Roboto, sans-serif;")
                .doesNotContain("@font-face");
    }

    // Covers: 24.3 (the theme toggle: a header button with aria-pressed and a fixed label; the page starts light and
    // turns dark only on a stored "dark" or a press; anything else in storage, or storage that throws, is light)
    @Test
    void theThemeToggle_isAHeaderButton_rememberedPerBrowser_andLightUnlessChosen() throws IOException {
        String html = read("index.html");
        String theme = block(read("console.js"), "theme");
        String header = html.substring(html.indexOf("<header>"), html.indexOf("</header>"));

        assertThat(header).contains("<button type=\"button\" id=\"theme-toggle\" aria-pressed=\"false\">Dark theme</button>");
        assertThat(theme).contains("function storedTheme() {")
                .contains("return localStorage.getItem(THEME_KEY) === \"dark\" ? \"dark\" : \"light\";")
                .contains("} catch (e) {\n    return \"light\";")
                .contains("document.documentElement.setAttribute(\"data-theme\", theme);")
                .contains("$(\"theme-toggle\").setAttribute(\"aria-pressed\", String(theme === \"dark\"));")
                .contains("applyTheme(storedTheme());")
                .contains("$(\"theme-toggle\").addEventListener(\"click\", toggleTheme);");
        assertThat(theme.substring(theme.indexOf("function toggleTheme() {"))).contains("localStorage.setItem(THEME_KEY, ")
                .contains("} catch (e) {");
    }

    // Covers: 24.3, 9.1 P9, 23.2 (the stepper's ending comes from the failure_reason in plain text, never the code: each
    // row of 24.3's table; the two code families by prefix; P9 names the refused total and the budget from the intent
    // answer, as text; any other reason falls back to the state's text)
    @Test
    void theEnding_isTheReasonInPlainText_neverTheCode() throws IOException {
        String steps = block(read("console.js"), "milestones");
        java.util.Map<String, String> rows = new java.util.LinkedHashMap<>();
        rows.put("approval_rejected", "Rejected by you");
        rows.put("approval_expired", "The approval expired: no order was placed");
        rows.put("handoff_timeout", "The handoff to the store timed out: no order was placed");
        rows.put("price_unstable", "The store's price kept changing: no order was placed");
        rows.put("policy_P1", "This store is not allowed: no order was placed");
        rows.put("policy_P2", "Above the limit for one purchase, or in a currency with no limit set: no order was placed");
        rows.put("policy_P3", "Above your limit for the last 24 hours: no order was placed");
        rows.put("policy_P4", "Too many items or too large a quantity: no order was placed");
        rows.put("policy_P5", "Too many proposals in the last hour: no order was placed");
        rows.put("policy_I12", "The agent buys only at stores the operator owns: no order was placed");
        rows.put("out_of_stock", "Out of stock at the store: no order was placed");
        rows.put("item_unavailable", "The item is not available at the store: no order was placed");
        rows.put("quantity_adjusted", "The store changed the quantity: no order was placed");
        rows.put("quote_lines_differ", "The store's quote did not match the request: no order was placed");
        rows.put("quote_unsupported", "The store's checkout needs something the agent cannot provide: no order was placed");
        rows.put("fulfillment_unsupported", "The store offered no delivery the agent can choose: no order was placed");
        rows.put("quote_retries_exhausted", "The store did not answer with a quote: no order was placed");
        rows.put("create_rejected", "The store refused the checkout: no order was placed");
        rows.put("update_rejected", "The store refused the checkout: no order was placed");
        rows.put("payment_declined", "The payment was declined: no order was placed");
        rows.put("payment_invalid", "The payment was not accepted: no order was placed");
        rows.put("canceled_by_merchant", "The store cancelled the checkout: no order was placed");
        rows.put("complete_unavailable", "This store does not take orders from the agent: no order was placed");
        rows.put("session_gone", "The checkout is no longer available at the store: no order was placed");
        rows.put("continue_url_not_allowed", "The store's checkout link was not allowed: no order was placed");
        rows.put("outcome_unknown", "Stopped: the store did not confirm whether an order was placed; a person needs to check");
        rows.put("approval_invalid", "Stopped: the approval could not be confirmed; a person needs to check");
        rows.put("merchant_changed", "Stopped: the store's settings changed; a person needs to check");
        rows.put("merchant_not_owned", "Stopped: the store's settings changed; a person needs to check");
        rows.put("handoff_status_unexpected",
                "Stopped: the store reported an unexpected checkout status; a person needs to check");

        assertThat(steps).as("a table of reason texts").contains("const REASON_TEXT = new Map([");
        String table = steps.substring(steps.indexOf("const REASON_TEXT = new Map(["), steps.indexOf("]);", steps.indexOf("const REASON_TEXT")));
        rows.forEach((code, text) -> assertThat(table).as(code).contains("[\"" + code + "\", \"" + text + "\"]"));
        assertThat(table.split("\\],", -1)).as("one entry per row, no other").hasSize(rows.size());
        assertThat(steps).contains("[\"merchant_error_\", \"The store refused the checkout: no order was placed\"]")
                .contains("[\"complete_error:\", \"The store refused the order: no order was placed\"]")
                .contains("\"Over your budget: the store quoted \" + money(quoted.total_minor, quoted.currency)\n"
                        + "      + \", your budget is \" + money(budget.amount_minor, budget.currency);")
                .contains("return \"Over your budget: no order was placed\";")
                .contains("if (reason === \"policy_P9\") {")
                .contains("return prefixed ? prefixed[1] : ENDINGS.get(intent.state);")
                .contains("const ending = ENDINGS.has(intent.state) ? endingText(intent) : \"\";");
        assertThat(steps).as("the code itself is never a label").doesNotContain("label: intent.failure_reason")
                .doesNotContain("label: reason");
    }

    // Covers: 24.3 (the header: the app name, the subtitle, the merchant-mode badge, the user chip and Sign out; the
    // badge's domain is set as text only, from the followed intent's merchant or, before one, the lab's list)
    @Test
    void header_hasTheSubtitle_andAMerchantModeBadge_fromTheIntentOrTheLabList() throws IOException {
        String html = read("index.html");
        String js = read("console.js");
        String mode = block(js, "merchant-mode");

        assertThat(html).contains("<h1>UCP Checkout Lab</h1>")
                .contains("<p class=\"subtitle\">Agent shopping with your approval</p>")
                .contains("<span id=\"merchant-mode\" class=\"badge\" hidden></span>")
                .contains("id=\"who\"").contains("id=\"sign-out\"");
        assertThat(html.indexOf("id=\"merchant-mode\"")).as("the badge before the user chip")
                .isLessThan(html.indexOf("id=\"who\""));
        assertThat(mode).contains("currentIntent.merchant.shop_domain")
                .contains("labMerchants.find((m) => m.live === true && typeof m.shop_domain === \"string\")")
                .contains("badge.textContent = domain ? \"Live store · \" + domain : \"Lab merchants\";")
                .contains("badge.className = domain ? \"badge live\" : \"badge\";");
        assertThat(js).contains("currentIntent.merchant = result.data.merchant;").contains("labMerchants = list.data.merchants;");
        assertThat(count(js, "shop_domain")).as("shop_domain only in the badge's code").isEqualTo(count(mode, "shop_domain"));
        assertThat(read("console.css")).contains(".badge.live {");
    }

    // Covers: 24.3, 23, 26.6 (the header: a small fixed "Model: Gemini" label next to the merchant-mode badge, shown only
    // when the service's /api/me says agent_model gemini; its text is fixed in the page, never set from data, and the
    // model is never kept in the browser)
    @Test
    void header_showsAModelLabel_onlyWhenTheServiceRunsGemini() throws IOException {
        String html = read("index.html");
        String js = read("console.js");
        String label = block(js, "model-label");

        assertThat(html).contains("<span id=\"model-label\" class=\"badge model\" hidden>Model: Gemini</span>");
        assertThat(html.indexOf("id=\"model-label\"")).as("right after the merchant-mode badge")
                .isGreaterThan(html.indexOf("id=\"merchant-mode\""))
                .isLessThan(html.indexOf("id=\"theme-toggle\""));
        assertThat(label).contains("function showModel(me) {")
                .contains("$(\"model-label\").hidden = me.agent_model !== \"gemini\";");
        assertThat(block(js, "model-label").indexOf("textContent")).as("the label's text is the page's own").isNegative();
        assertThat(count(js, "agent_model")).as("agent_model only in the label's code").isEqualTo(count(label, "agent_model"));
        assertThat(js).contains("  showModel(me.data);\n");
        assertThat(read("console.css")).contains(".badge.model {");
    }

    // Covers: 24.3 (the Developer tools: the fault lab and the counters inside one details element, closed and hidden
    // until the lab answers, and hidden whenever the merchant shown is a live store)
    @Test
    void developerTools_holdTheFaultLabAndCounters_andAreHiddenForALiveStore() throws IOException {
        String html = read("index.html");
        String mode = block(read("console.js"), "merchant-mode");
        int tools = html.indexOf("<details id=\"dev-tools\" class=\"dev-tools\" hidden>");
        int end = html.indexOf("</details>", tools);

        assertThat(tools).as("a closed, hidden details").isPositive();
        assertThat(html.substring(tools, end)).contains("<summary>Developer tools</summary>")
                .contains("<section id=\"fault-lab\"").contains("<section id=\"counters\"").doesNotContain(" open");
        assertThat(html).doesNotContain("id=\"fault-lab\" aria-labelledby=\"lab-h\" hidden")
                .doesNotContain("id=\"counters\" aria-labelledby=\"orders-h\" hidden");
        assertThat(mode).contains("$(\"dev-tools\").hidden = !labAvailable || domain !== \"\";");
        assertThat(read("console.js")).doesNotContain("$(\"fault-lab\").hidden").doesNotContain("$(\"counters\").hidden");
    }

    // Covers: 24.3 (chat bubbles by role, and each tool call as a chip "<tool> · <took_ms> ms" with a status dot that
    // is never the only signal: a summary other than ok is shown as text)
    @Test
    void chat_hasBubblesByRole_andToolCallsAsChipsWithAStatusDot() throws IOException {
        String js = read("console.js");
        String css = read("console.css");

        assertThat(js).contains("el(\"span\", null, e.summary === \"ok\" ? \"dot ok\" : \"dot warn\")")
                .contains(".setAttribute(\"aria-hidden\", \"true\");")
                .contains("e.tool + \" · \" + e.took_ms + \" ms\" + (e.summary === \"ok\" ? \"\" : \" · \" + e.summary)");
        assertThat(css).contains(".chat li.user {").contains(".chat li.agent {").contains(".chip .dot.ok {")
                .contains(".chip .dot.warn {");
    }

    // Covers: 24.3 (ask_user's time is the user's own: its chip reads "ask_user · waited for your answer", never a
    // duration; every other tool keeps "<tool> · <took_ms> ms")
    @Test
    void chip_forAskUser_saysItWaited_insteadOfADuration() throws IOException {
        String js = read("console.js");

        assertThat(js).contains("function chipText(e) {")
                .contains("  if (e.tool === \"ask_user\") {\n    return \"ask_user · waited for your answer\""
                        + " + (e.summary === \"ok\" ? \"\" : \" · \" + e.summary);\n  }\n")
                .contains("chip.appendChild(el(\"span\", chipText(e)));");
    }

    // Covers: I2, 9.3 L4, 24.3, 9.2 (the approval card is its own component and reads only the approval response's
    // fields: the snapshot's lines with their merchant titles, currency and total, the expiry and the token, and at a link
    // merchant the answer's seller and ships_to (23.2, 26.7); never the chat, the run, the messages or a description)
    @Test
    void approvalCard_readsOnlyTheApprovalResponsesFields() throws IOException {
        String js = read("console.js");
        String card = card(js);

        Set<String> allowed = Set.of("snapshot", "lines", "item_id", "title", "quantity", "unit_price_minor", "currency",
                "total_minor", "expires_at", "token", "merchant_id", "completion", "note", "budget", "seller", "ships_to",
                "options");
        Set<String> read = new LinkedHashSet<>();
        Matcher fields = Pattern.compile("\\b(?:approval|snapshot|line)\\.([A-Za-z_]+)").matcher(card);
        while (fields.find()) {
            read.add(fields.group(1));
        }
        assertThat(read).as("fields the card reads").isNotEmpty().isSubsetOf(allowed);
        for (String other : List.of("messages", "chat", "tool_events", "run_id", "intent_ids", "/api/runs", "reason",
                "rationale", "description", "model")) {
            assertThat(card).as("the card's code names " + other).doesNotContain(other);
        }
        assertThat(card.replace("line.title", "")).as("a title only as the snapshot line's").doesNotContain("title");
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

    // Covers: 24.3, I2, 9.3 L4 (the card is headed "Approval needed / From <merchant_id>" from the snapshot, the countdown
    // is a pill, a line shows quantity × unit price and its total, the last row is the large total, the notes are an
    // info box, and the primary button names the snapshot's total)
    @Test
    void approvalCard_isHeadedApprovalNeeded_withAPill_anInfoBox_andApproveNamingTheTotal() throws IOException {
        String card = card(read("console.js"));

        assertThat(card).contains("heading.appendChild(el(\"h3\", \"Approval needed\"));")
                .contains("heading.appendChild(el(\"p\", \"From \" + snapshot.merchant_id, \"muted\"));")
                .contains("const expiry = el(\"span\", null, \"pill\");")
                .contains("line.quantity + \" × \" + money(line.unit_price_minor, snapshot.currency)")
                .contains("const row = el(\"tr\", null, last ? \"total\" : null);")
                .contains("const info = el(\"div\", null, \"info\");")
                .contains("info.appendChild(el(\"p\", \"Price and stock are not held.\"));")
                .contains("const approve = el(\"button\", \"Approve \" + money(snapshot.total_minor, snapshot.currency), \"primary\");")
                .doesNotContain("el(\"h3\", \"Approval from \"");
        assertThat(read("console.css")).contains(".pill {").contains(".info {").contains("tr.total")
                .contains("button.primary {").contains(".item-id {\n  font-family: ui-monospace");
    }

    // Covers: 24.3, 9.2, I2 (a line shows the snapshot's merchant title as text, with the item id under it, and the id
    // alone when the title is empty)
    @Test
    void approvalCard_showsTheLinesTitle_withTheItemIdUnderIt() throws IOException {
        String card = card(read("console.js"));

        // 24.3: the line's options sit between the title and the item id (approvalCard_showsTheLinesOptions_...).
        assertThat(card).contains("    const name = el(\"td\");\n"
                + "    if (line.title) {\n      name.appendChild(el(\"div\", line.title));\n    }\n")
                .contains("    name.appendChild(el(\"div\", line.item_id, \"item-id\"));\n"
                        + "    row.appendChild(name);\n")
                .doesNotContain("row.appendChild(el(\"td\", line.item_id));");
        assertThat(card.indexOf("name.appendChild(el(\"div\", line.title));"))
                .isLessThan(card.indexOf("name.appendChild(el(\"div\", line.item_id, \"item-id\"));"));
        assertThat(read("console.css")).contains(".item-id {");
    }

    // Covers: 24.3, 9.2, I2, 26.7 (a line's options show under its title as "<name>: <label>", each set as text from the
    // snapshot line only, before the item id)
    @Test
    void approvalCard_showsTheLinesOptions_asText_underTheTitle() throws IOException {
        String card = card(read("console.js"));

        assertThat(card).contains("    (line.options || []).forEach((option) => {\n"
                + "      name.appendChild(el(\"div\", option.name + \": \" + option.label, \"option\"));\n"
                + "    });\n"
                + "    name.appendChild(el(\"div\", line.item_id, \"item-id\"));\n");
        assertThat(card.indexOf("line.options")).isGreaterThan(card.indexOf("line.title"))
                .isLessThan(card.indexOf("line.item_id"));
    }

    // Covers: 24.3, 19 (at a handoff merchant the store adds shipping and tax after the handoff: no "Other charges (tax)"
    // line, an "Other charges" line only when the total differs from the lines' sum, and a total labelled as before the
    // store's shipping and tax; an agent merchant's card keeps "Other charges (tax)" and "Total")
    @Test
    void approvalCard_atAHandoffMerchant_hasNoTaxLine_andItsTotalIsBeforeTheStoresShippingAndTax() throws IOException {
        String card = card(read("console.js"));

        assertThat(card).contains("function cardCharges(snapshot, handoff, linesTotal) {\n"
                + "  const rows = [];\n"
                + "  const other = snapshot.total_minor - linesTotal;\n"
                + "  if (!handoff) {\n    rows.push([\"Other charges (tax)\", other]);\n"
                + "  } else if (other !== 0) {\n    rows.push([\"Other charges\", other]);\n  }\n"
                + "  rows.push([handoff ? \"Total before the store's shipping and tax\" : \"Total\", snapshot.total_minor]);\n"
                + "  return rows;\n}\n");
        assertThat(card).contains("const rows = cardCharges(snapshot, approval.completion === \"handoff\", linesTotal);");
        assertThat(count(card, "Other charges (tax)")).as("only in cardCharges' agent branch").isEqualTo(1);
    }

    // Covers: 23.2, 24.3, H5 C7c (a handoff merchant's card shows the answer's note, our fixed text, as text; only when
    // the answer's completion is handoff)
    @Test
    void approvalCard_showsTheHandoffNote_onlyAtAHandoffMerchant() throws IOException {
        String card = card(read("console.js"));

        assertThat(card).contains("if (approval.completion === \"handoff\") {")
                .contains("info.appendChild(el(\"p\", approval.note));");
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

    // Covers: 24.3, 23.2, I9, H5, 7.6 (the Continue at the store link: built only in HANDED_OFF, from the handoff_url of
    // the owner's GET /api/intents/{id}, read once and nowhere else, while keepsHandoffLink says so; removed when another
    // intent is watched; a link in a new tab with rel="noopener noreferrer" and referrerpolicy="no-referrer")
    @Test
    void handoffLink_existsOnlyInHandedOff_fromTheIntentsHandoffUrl() throws IOException {
        String js = read("console.js");
        String link = handoffLink(js);

        assertThat(count(js, "handoff_url")).as("handoff_url is read once").isEqualTo(1);
        // 24.3, 7.6: cleared in any other state; in HANDED_OFF, kept as keepsHandoffLink says (the poll's test).
        assertThat(js).contains("    if (state !== \"HANDED_OFF\") {\n      clear($(\"handoff\"));\n"
                + "      clear($(\"follow-up\"));\n    }\n");
        assertThat(count(js, "showHandoffLink(")).as("one definition, one call").isEqualTo(2);
        int watch = js.indexOf("function watchIntent(id) {");
        assertThat(js.indexOf("clear($(\"handoff\"));", watch)).as("watching another intent drops the link")
                .isBetween(watch, js.indexOf("pollIntent();", watch));
        assertThat(link).contains("const anchor = el(\"a\", \"Continue at the store\");")
                .contains("anchor.href = url;").contains("anchor.target = \"_blank\";")
                .contains("anchor.rel = \"noopener noreferrer\";")
                .contains("anchor.referrerPolicy = \"no-referrer\";");
    }

    // Covers: 24.3, 23.2, 26.7 (a link merchant's card: "From <seller.name>" with a Shopify catalog badge, the total
    // row Listed price, no Other charges line, the answer's note, and the button Approve and get the link; all as text)
    @Test
    void approvalCard_atALinkMerchant_readsAsTheDesignSays() throws IOException {
        String card = card(read("console.js"));

        assertThat(card).contains("approval.completion === \"link\"")
                .contains("el(\"p\", \"From \" + approval.seller.name")
                .contains("\"Shopify catalog\"")
                .contains("\"Listed price\"")
                .contains("\"Approve and get the link\"");
        assertThat(count(card, "Other charges (tax)")).as("only in cardCharges' agent branch").isEqualTo(1);
    }

    // Covers: 24.3, 26.7, I9, D5 (in HANDED_OFF a link intent's link reads Continue at <seller.host>, with the same
    // anchor rules, and the fixed text that the agent can't see the purchase; the stepper's link steps)
    @Test
    void handoffLink_atALinkMerchant_namesTheSellersHost_andSaysTheAgentCantSee() throws IOException {
        String js = read("console.js");
        String link = handoffLink(js);

        assertThat(link).contains("\"Continue at \" + ").contains("seller.host")
                .contains("\"If you buy, the store emails you. The agent can't see this purchase.\"")
                .contains("anchor.rel = \"noopener noreferrer\";")
                .contains("anchor.referrerPolicy = \"no-referrer\";");
        assertThat(count(js, "handoff_url")).as("handoff_url is read once").isEqualTo(1);
        assertThat(js).contains("\"Priced from Shopify's catalog\"").contains("\"Link ready\"");
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

        assertThat(block).contains("function followUpText(order, followUp) {")
                .contains("\"Order placed at the store, confirmation #\" + order.label")
                .contains("\"Waiting for the store to report an order\"")
                .contains("\"The store reported no order within 24 h\"")
                .contains("function showSuccess(text, quote) {")
                .contains("card.appendChild(el(\"p\", text, \"success-title\"));");
        for (String forbidden : List.of("innerHTML", "insertAdjacentHTML", "permalink", "console.", "Storage")) {
            assertThat(block).as("the follow-up's code uses " + forbidden).doesNotContain(forbidden);
        }
        assertThat(js.toLowerCase(java.util.Locale.ROOT)).as("the console never says paid").doesNotContain("paid");
        assertThat(read("index.html")).as("outside the one live region, as the link is").contains("<div id=\"follow-up\"></div>");
    }

    // Covers: 24.3, 7.6 (in HANDED_OFF the follow-up and the link are shown on every poll, not only when the state
    // changes; whether the link stays is decided by keepsHandoffLink, from the store's order and the follow-up)
    @Test
    void followUp_isShownOnEveryPoll_andTheLinkFollowsIt() throws IOException {
        String js = read("console.js");

        assertThat(js).contains("  if (state === \"HANDED_OFF\") {\n"
                + "    showSuccess(result.data.store_order ? followUpText(result.data.store_order, result.data.follow_up) : \"\",\n"
                + "        result.data.quote);\n"
                + "    if (keepsHandoffLink(result.data.store_order, result.data.follow_up)) {\n"
                + "      showHandoffLink(result.data.handoff_url);\n    } else {\n      clear($(\"handoff\"));\n    }\n"
                + "  } else if (state === \"COMPLETED\") {\n");
        assertThat(followUp(js)).contains("function keepsHandoffLink(order, followUp) {\n"
                + "  if (order) {\n    return false;\n  }\n"
                + "  if (followUp && !followUp.watching) {\n    return LINK_KEPT_WHEN_ENDED.includes(followUp.ended_reason);\n  }\n"
                + "  return true;\n}\n");
        int watch = js.indexOf("function watchIntent(id) {");
        assertThat(js.indexOf("clear($(\"follow-up\"));", watch)).as("watching another intent drops the follow-up")
                .isBetween(watch, js.indexOf("pollIntent();", watch));
    }

    // Covers: 24.3, 7.6 (a follow-up that ended without an order shows the text of its ended_reason, looked up in a Map so
    // no other key can match; the Continue at the store link stays only for window_ended and reads_exhausted, where the
    // checkout may still be open, and goes for canceled, session_gone and merchant_changed; never "not paid")
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "window_ended     | The store reported no order within 24 h                      | true",
        "reads_exhausted  | The store reported no order within 24 h                      | true",
        "canceled         | The checkout was cancelled at the store; no order was placed | false",
        "session_gone     | The checkout is no longer available at the store             | false",
        "merchant_changed | Stopped checking: the store's settings changed               | false"})
    void followUp_endedReason_showsItsText_andKeepsTheLinkOnlyWhereTheCheckoutMayBeOpen(String reason, String text,
            boolean keepsLink) throws IOException {
        String block = followUp(read("console.js"));

        assertThat(block).contains("  [\"" + reason + "\", \"" + text + "\"],\n");
        Matcher kept = Pattern.compile("const LINK_KEPT_WHEN_ENDED = \\[([^\\]]*)\\];").matcher(block);
        assertThat(kept.find()).as("the reasons that keep the link").isTrue();
        assertThat(kept.group(1).contains("\"" + reason + "\"")).as("keeps the link").isEqualTo(keepsLink);
        assertThat(block).contains("    text = FOLLOW_UP_ENDED.get(followUp.ended_reason) || \"\";")
                .doesNotContain("The store reported no order within 24 h\";");
        assertThat(text.toLowerCase(java.util.Locale.ROOT)).doesNotContain("paid");
    }

    // Covers: 24.3, 7.1, 7.6, P7 (the timeline is a stepper of milestones made from the intent's state, merchant
    // completion, approval and follow-up, never from an event's name; each step is marked done, current, pending or
    // ended, in text for a screen reader as well as by its marker; the events are behind a "Show all N events" toggle)
    @Test
    void timeline_isAStepperOfMilestones_withTheEventsBehindAToggle() throws IOException {
        String js = read("console.js");
        String steps = block(js, "milestones");
        String html = read("index.html");

        for (String label : List.of("\"Proposed\"", "\"Quoted by the store\"", "\"Waiting for your approval\"",
                "\"Approved by you\"", "\"Handed off to the store\"", "\"Sent to the store\"",
                "\"Order placed at the store\"", "[\"REJECTED\", \"Rejected: no order was placed\"]",
                "[\"FAILED_NOT_CHARGED\", \"Stopped: no order was placed\"]",
                "[\"ESCALATED\", \"Stopped: the outcome needs a person to check\"]")) {
            assertThat(steps).as("the stepper names " + label).contains(label);
        }
        assertThat(steps).contains("intent.merchant && intent.merchant.completion === \"handoff\"")
                .contains("followUpText(intent.store_order, intent.follow_up)")
                .contains("item.setAttribute(\"aria-current\", \"step\");")
                .doesNotContain("event").doesNotContain("LABELS").doesNotContain("describe(");
        assertThat(js).contains("showSteps(milestones(result.data));")
                .contains("$(\"events-toggle\").textContent = \"Show all \" + result.data.length + \" events\";");
        int events = html.indexOf("<details id=\"all-events\" hidden>");
        assertThat(events).as("the events behind a closed toggle, hidden until an intent is followed").isPositive();
        assertThat(html.substring(events, html.indexOf("</details>", events)))
                .contains("<summary id=\"events-toggle\">Show all events</summary>")
                .contains("<ol id=\"timeline\" class=\"timeline\"></ol>");
        assertThat(html).contains("<ol id=\"steps\" class=\"steps\" aria-label=\"Milestones\"></ol>");
        assertThat(read("console.css")).contains(".steps li.done").contains(".steps li.current")
                .contains(".steps li.pending").contains(".steps li.ended");
    }

    // Covers: 24.3 (before the console follows an intent the Purchase panel reads "No purchase yet", with no toggle and
    // never "Show all 0 events"; the toggle appears with the followed intent's events and hides again on a 404)
    @Test
    void purchasePanel_withNoIntent_saysNoPurchaseYet_andHidesTheToggle() throws IOException {
        String html = read("index.html");
        String js = read("console.js");

        assertThat(html).contains("<p id=\"timeline-state\" class=\"muted\">No purchase yet</p>")
                .contains("<details id=\"all-events\" hidden>").doesNotContain("Show all 0 events");
        int load = js.indexOf("async function loadEvents(id) {");
        assertThat(load).isNotNegative();
        assertThat(js.substring(load, js.indexOf("\n}\n", load)))
                .contains("$(\"all-events\").hidden = false;");
        int notFound = js.indexOf("if (result.status === 404) {", js.indexOf("async function pollIntent() {"));
        assertThat(notFound).isNotNegative();
        assertThat(js.substring(notFound, js.indexOf("return;", notFound)))
                .contains("$(\"all-events\").hidden = true;");
    }

    // Covers: 24.3, 7.6, VERIFY 11 (the success card: in COMPLETED, and in HANDED_OFF once the store reported an order,
    // the text and the product lines of the intent's quote, each set as text; green only here)
    @Test
    void successCard_showsTheOrderText_andTheQuotesProductLines() throws IOException {
        String js = read("console.js");
        String block = followUp(js);

        assertThat(block).contains("const card = el(\"div\", null, \"success\");")
                .contains("quote.lines.forEach((line) => {")
                .contains("(line.title || line.item_id) + \" · \" + line.quantity + \" × \"");
        assertThat(js).contains("  } else if (state === \"COMPLETED\") {\n"
                + "    showSuccess(\"Order placed at the store\", result.data.quote);\n  }\n");
        String css = read("console.css");
        assertThat(css).contains(".success {");
        assertThat(count(css.toLowerCase(java.util.Locale.ROOT), "#1a7f37")).as("green only as the success token")
                .isEqualTo(1);
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

    // Covers: 24.3, 23.1, P9 (the send form has an optional Budget field, an amount and a currency, each with a label;
    // a 44 px target like every control)
    @Test
    void sendForm_hasALabelledBudgetField_withACurrency() throws IOException {
        String html = read("index.html");
        String form = html.substring(html.indexOf("<form id=\"send\""), html.indexOf("</form>"));

        assertThat(form).contains("<label for=\"budget\">Budget (optional)</label>")
                .contains("<input id=\"budget\" name=\"budget\" type=\"text\" inputmode=\"decimal\" maxlength=\"16\">")
                .contains("<label for=\"budget-currency\" class=\"sr-only\">Budget currency</label>")
                .contains("<select id=\"budget-currency\" name=\"budget-currency\">"
                        + "<option>USD</option><option>EUR</option><option>GBP</option></select>");
        assertThat(form.indexOf("id=\"budget\"")).as("the field follows the message box")
                .isGreaterThan(form.indexOf("id=\"message\""));
    }

    // Covers: 24.3, 23.1 (beside the Budget field, an always-visible, labelled Deliver to select)
    @Test
    void sendForm_hasADeliverToSelect_alwaysVisible() throws IOException {
        String html = read("index.html");
        String form = html.substring(html.indexOf("<form id=\"send\""), html.indexOf("</form>"));

        assertThat(form).contains("<label for=\"ship-to\">Deliver to</label>")
                .contains("<select id=\"ship-to\" name=\"ship-to\"></select>");
        assertThat(form.indexOf("id=\"ship-to\"")).as("it follows the budget's currency")
                .isGreaterThan(form.indexOf("id=\"budget-currency\""));
        String select = form.substring(form.indexOf("<select id=\"ship-to\""));
        assertThat(select.substring(0, select.indexOf(">"))).doesNotContain("hidden").doesNotContain("disabled");
    }

    // Covers: 24.3, 23.1, 18 (Deliver to lists GET /api/countries by name, as text, and starts at /api/me's
    // buyer_country; it keeps nothing in browser storage, where the theme stays the only stored value)
    @Test
    void deliverTo_listsTheCountries_asText_andStartsAtTheOperatorsDefault() throws IOException {
        String js = read("console.js");
        String shipTo = block(js, "ship-to");

        assertThat(shipTo).contains("await api(\"GET\", \"/api/countries\")")
                .contains("const option = el(\"option\", country.name);")
                .contains("option.value = country.country;")
                .contains("$(\"ship-to\").value = me.buyer_country.country;")
                .doesNotContain("localStorage");
        assertThat(js).contains("loadCountries(me.data);");
    }

    // Covers: 24.3, 23.1 (changing Deliver to sets the Budget field's currency to the country's, adding it to the list
    // when it is not there; a new run sends the country, a continuation does not, and it is disabled while a run
    // continues)
    @Test
    void deliverTo_switchesTheBudgetsCurrency_andIsSentOnANewRunOnly() throws IOException {
        String js = read("console.js");
        String shipTo = block(js, "ship-to");

        assertThat(shipTo).contains("function onShipToChange() {")
                .contains("$(\"budget-currency\").appendChild(el(\"option\", country.currency));")
                .contains("$(\"budget-currency\").value = country.currency;");
        assertThat(js).contains("$(\"ship-to\").addEventListener(\"change\", onShipToChange);")
                .contains("const shipTo = continuing ? {} : {ship_to_country: $(\"ship-to\").value};")
                .contains("$(\"ship-to\").disabled = continuingRun();");
    }

    // Covers: 24.3 (money in the currency's own minor digits: shown that way, and the Budget field's amount turned into
    // minor units with the same digits)
    @Test
    void money_andTheBudgetField_useTheCurrencysOwnMinorDigits() throws IOException {
        String js = read("console.js");

        assertThat(js).contains("function minorDigits(currency) {")
                .contains("new Intl.NumberFormat(\"en\", {style: \"currency\", currency: currency})"
                        + ".resolvedOptions().maximumFractionDigits");
        String money = js.substring(js.indexOf("function money(minor, currency) {"));
        money = money.substring(0, money.indexOf("\n}\n"));
        assertThat(money).contains("minorDigits(currency)").doesNotContain("/ 100");
        String toSend = block(js, "budget").substring(block(js, "budget").indexOf("function budgetToSend() {"));
        assertThat(toSend.substring(0, toSend.indexOf("\n}\n"))).contains("minorDigits(").doesNotContain("* 100");
    }

    // Covers: 24.3 money format (the console formats every amount from its minor units as a string of digits, never
    // through floating point: the browser's CLDR en symbol and digits, Indian grouping for INR and threes otherwise; the
    // Budget field is shown and read the same way; nothing else formats an amount)
    @Test
    void money_isFormattedFromMinorUnits_withTheSymbol_andTheCurrencysGrouping() throws IOException {
        String js = read("console.js");
        String money = block(js, "money");

        assertThat(money).contains("function money(minor, currency) {")
                .contains("function currencySymbol(currency) {")
                .contains(".formatToParts(0).find((part) => part.type === \"currency\")")
                .contains("function grouped(whole, currency) {")
                .contains("const size = currency === \"INR\" ? 2 : 3;")
                .contains("function plainAmount(minorText, digits) {")
                .doesNotContain("toFixed").doesNotContain("Math.pow").doesNotContain("Number(").doesNotContain(" / ")
                .doesNotContain("parseFloat");
        assertThat(js).as("no amount through floating point anywhere").doesNotContain("toFixed(")
                .doesNotContain("Math.pow(10");
        String showRunBudget = js.substring(js.indexOf("function showRunBudget(budget) {"));
        assertThat(showRunBudget.substring(0, showRunBudget.indexOf("\n}\n")))
                .contains("plainAmount(String(budget.amount_minor), minorDigits(budget.currency))");
        String toSend = js.substring(js.indexOf("function budgetToSend() {"));
        assertThat(toSend.substring(0, toSend.indexOf("\n}\n")))
                .contains("const minor = Number(m[1] + (m[2] || \"\").padEnd(digits, \"0\"));");
    }

    // Covers: 24.3, 23.2, 26.7 (the link card says where the catalog listed the item as shipping to, from the approval
    // answer's ships_to, as text)
    @Test
    void approvalCard_atALinkMerchant_saysListedAsShippingTo() throws IOException {
        String card = card(read("console.js"));

        assertThat(card).contains("if (approval.ships_to) {")
                .contains("el(\"p\", \"Listed as shipping to \" + approval.ships_to.name");
    }

    /** The pre-fill's pattern: console.js's {@code BUDGET_LIMIT} literal, compiled as the browser reads it. */
    private static Pattern budgetPattern() throws IOException {
        String budget = block(read("console.js"), "budget");
        String start = "const BUDGET_LIMIT = /";
        int from = budget.indexOf(start);
        assertThat(from).as("BUDGET_LIMIT is one regex literal").isNotNegative();
        int to = budget.indexOf("/i;\n", from);
        assertThat(to).as("the literal ends with /i;").isGreaterThan(from);
        return Pattern.compile(budget.substring(from + start.length(), to),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /** The amount and the sign the pattern finds in {@code text}, as budgetFromMessage picks them, or null. */
    private static List<String> found(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) {
            return null;
        }
        String amount = m.group(2) != null ? m.group(2) : m.group(5) != null ? m.group(5) : m.group(8);
        String sign = java.util.stream.Stream.of(1, 3, 4, 6, 7, 9).map(m::group).filter(java.util.Objects::nonNull)
                .findFirst().orElse("");
        return List.of(amount, sign.toLowerCase(java.util.Locale.ROOT));
    }

    /** console.js's {@code BUDGET_NOT_MONEY} literal: a word after a sign-less amount that says it is not money. */
    private static Pattern notMoneyPattern() throws IOException {
        String budget = block(read("console.js"), "budget");
        String start = "const BUDGET_NOT_MONEY = /";
        int from = budget.indexOf(start);
        assertThat(from).as("BUDGET_NOT_MONEY is one regex literal").isNotNegative();
        int to = budget.indexOf("/i;\n", from);
        assertThat(to).as("the literal ends with /i;").isGreaterThan(from);
        return Pattern.compile(budget.substring(from + start.length(), to),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /**
     * The amount and currency budgetFromMessage fills from {@code text} with {@code selected} as the field's currency, or
     * null, step for step as the pinned lines below read: the first match; a sign or code names the currency; a sign-less
     * amount after a limit word is money only from 100 up and with no unit after it.
     */
    private static List<String> prefill(String text, String selected) throws IOException {
        Matcher m = budgetPattern().matcher(text);
        if (!m.find()) {
            return null;
        }
        String amount = m.group(2) != null ? m.group(2) : m.group(5) != null ? m.group(5) : m.group(8);
        String sign = java.util.stream.Stream.of(1, 3, 4, 6, 7, 9).map(m::group).filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        boolean named = m.group(8) != null;
        if (sign == null && !named && (new java.math.BigDecimal(amount.replace(",", "")).compareTo(
                java.math.BigDecimal.valueOf(100)) < 0 || notMoneyPattern().matcher(text.substring(m.end())).find())) {
            return null;
        }
        java.util.Map<String, String> signs = java.util.Map.of("$", "USD", "€", "EUR", "£", "GBP", "₹", "INR", "rs", "INR", "rs.", "INR",
                "rupee", "INR", "rupees", "INR");
        String currency = sign == null ? selected
                : signs.getOrDefault(sign.toLowerCase(java.util.Locale.ROOT), sign.toUpperCase(java.util.Locale.ROOT));
        return List.of(amount.replace(",", ""), currency);
    }

    // Covers: 24.3, P9, 23.1 (a sign-less amount after a limit word takes the selected budget currency: "shoes under
    // 15000" with INR fills 15000 INR, "under 15000" with USD 15000 USD; the field stays as it is for "under 2 kg", "kids
    // under 10", "under 3 days", "size 7 uk" and "under 50", for an amount of 100 or more with a unit after it, and an
    // amount with a sign is still taken in its own currency)
    @Test
    void budgetPrefill_aSignlessAmountAfterALimitWord_takesTheSelectedCurrency_unlessItIsNotMoney() throws IOException {
        assertThat(prefill("shoes under 15000", "INR")).containsExactly("15000", "INR");
        assertThat(prefill("under 15000", "USD")).containsExactly("15000", "USD");
        assertThat(prefill("a board below 1,50,000", "INR")).containsExactly("150000", "INR");
        assertThat(prefill("shoes up to 100", "EUR")).containsExactly("100", "EUR");
        for (String text : List.of("under 2 kg", "kids under 10", "under 3 days", "size 7 uk", "under 50",
                "a bag under 2500 g", "tent under 250 cm", "under 120 months", "under 200 pcs", "under 500 pack",
                "under 100 %", "under 150 x 3", "under 300 mm", "under 150 years", "under 120 yrs", "under 700 UK",
                "under 500 us", "under 450 EU", "under 100 size")) {
            assertThat(prefill(text, "INR")).as(text).isNull();
        }
        assertThat(prefill("under $50", "INR")).as("a sign keeps a small amount").containsExactly("50", "USD");
        assertThat(prefill("under 500 USD", "INR")).containsExactly("500", "USD");
        assertThat(prefill("my budget is 50", "INR")).as("after budget, any amount").containsExactly("50", "INR");
        String budget = block(read("console.js"), "budget");
        assertThat(budget).contains("const named = Boolean(m[8]);\n"
                + "  if (!sign && !named && (Number(amount.replace(/,/g, \"\")) < 100\n"
                + "      || BUDGET_NOT_MONEY.test(text.slice(m.index + m[0].length)))) {\n"
                + "    return null;\n  }\n")
                .contains("currency: sign ? budgetCurrency(sign) : $(\"budget-currency\").value");
    }

    // Covers: 24.3, P9 (the live check's rupee forms, each run through the console's own pattern: "500 rupees", "rs 500",
    // "Rs.500", "₹500" and "500 INR", each with a limit word; Indian grouping "1,50,000" and thousands "15,000"; "budget to
    // 15000" without a sign; and each sign maps to INR)
    @Test
    void budgetPrefill_readsRupeeForms_andIndianGrouping() throws IOException {
        Pattern p = budgetPattern();

        assertThat(found(p, "red nike shoes under 500 rupees")).containsExactly("500", "rupees");
        assertThat(found(p, "shoes below rs 500")).containsExactly("500", "rs");
        assertThat(found(p, "shoes max Rs.500")).containsExactly("500", "rs.");
        assertThat(found(p, "shoes under ₹500")).containsExactly("500", "₹");
        assertThat(found(p, "shoes for 500 INR or less")).containsExactly("500", "inr");
        assertThat(found(p, "a board under ₹1,50,000")).containsExactly("1,50,000", "₹");
        assertThat(found(p, "a board up to rs 15,000.50")).containsExactly("15,000.50", "rs");
        assertThat(found(p, "lets increase the budget to 15000")).containsExactly("15000", "");
        assertThat(found(p, "my budget is $80")).containsExactly("80", "$");
        assertThat(found(p, "under $500")).containsExactly("500", "$");
        assertThat(found(p, "red nike shoes")).isNull();
        assertThat(found(p, "7 UK")).isNull();
        String budget = block(read("console.js"), "budget");
        for (String sign : List.of("\"₹\": \"INR\"", "\"rs\": \"INR\"", "\"rs.\": \"INR\"", "\"rupee\": \"INR\"",
                "\"rupees\": \"INR\"")) {
            assertThat(budget).as(sign).contains(sign);
        }
        assertThat(budget).contains("BUDGET_SIGNS[sign.toLowerCase()] || sign.toUpperCase()")
                .contains("const named = Boolean(m[8]);")
                .contains("currency: sign ? budgetCurrency(sign) : $(\"budget-currency\").value");
    }

    // Covers: 24.3, 23.1 PATCH budget (on a follow-up, a field that differs from the run's current budget is sent as a
    // PATCH before the message; the status line then reads "Budget updated to <amount> for new proposals."; a refused
    // change sends no message)
    @Test
    void aFollowUp_withAChangedField_patchesTheRunsBudget_first() throws IOException {
        String js = read("console.js");
        String send = js.substring(js.indexOf("async function send(event) {"), js.indexOf("// <run-end>"));

        assertThat(send).contains("if (continuing && !fieldMatchesRun(budget)) {")
                .contains("await api(\"PATCH\", \"/api/runs/\" + currentRun.id + \"/budget\", budget)")
                .contains("budgetNote = changed.data.budget\n"
                        + "        ? \"Budget updated to \" + money(changed.data.budget.amount_minor, changed.data.budget.currency)\n"
                        + "            + \" for new proposals.\"\n"
                        + "        : \"Budget removed for new proposals.\";");
        assertThat(send.indexOf("\"PATCH\"")).as("the change goes first")
                .isLessThan(send.indexOf("\"/messages\""));
        assertThat(send.substring(send.indexOf("if (!changed.ok) {"))).startsWith("if (!changed.ok) {\n"
                + "      stopWorking();\n      setStatus(errorText(changed), true);\n      return;\n    }");
        String budget = block(js, "budget");
        assertThat(budget).contains("function fieldMatchesRun(field) {");
        assertThat(budget).contains("function showBudgetNote(run, statusText) {\n"
                + "  if (budgetNote && run.status !== \"running\") {\n"
                + "    setStatus(budgetNote + \" \" + statusText);\n");
        assertThat(js).contains("  showBudgetNote(run, statusText);\n");
    }

    // Covers: 24.3, 23.1, 21 (the run's current budget comes from the server's run answers only, never from what the page
    // sent; the page keeps the run in the URL fragment, never in storage, and on load reads GET /api/runs/{run_id} to
    // show the run and set the field from its budget, so a page loaded again after a change matches the server)
    @Test
    void theRunsBudget_comesFromTheServer_andAPageLoadedAgainRestoresIt() throws IOException {
        String js = read("console.js");
        String restore = block(js, "restore");

        assertThat(js).contains("runBudget = run.budget || null;")
                .contains("history.replaceState(null, \"\", \"#run=\" + encodeURIComponent(run.run_id));")
                .contains("await restoreRun();");
        assertThat(restore).contains("/^#run=([^&#]{1,128})$/.exec(location.hash)")
                .contains("const result = await api(\"GET\", \"/api/runs/\" + encodeURIComponent(id));")
                .contains("showRun(result.data);")
                .contains("showRunBudget(result.data.budget);")
                .doesNotContain("localStorage").doesNotContain("sessionStorage");
        String showRunBudget = js.substring(js.indexOf("function showRunBudget(budget) {"));
        showRunBudget = showRunBudget.substring(0, showRunBudget.indexOf("\n}\n"));
        assertThat(showRunBudget).contains("minorDigits(budget.currency)").contains("showCurrency(budget.currency);");
        assertThat(js.indexOf("runBudget = run.budget || null;")).as("set in showRun, from the run answer")
                .isGreaterThan(js.indexOf("function showRun(run) {"));
        assertThat(js.replace("runBudget = run.budget || null;", "").replace("let runBudget = null;", ""))
                .as("nothing else sets the run's budget").doesNotContain("runBudget = ");
    }

    // Covers: 24.3, 23.2, 23.1 PATCH budget (a pending card whose approval budget differs from the run's current budget
    // reads "Proposed under your earlier budget of <amount>", from the approval's budget, as text; Approve stays enabled)
    @Test
    void approvalCard_underAnEarlierBudget_saysSo_andStaysApprovable() throws IOException {
        String js = read("console.js");
        String card = card(js);

        assertThat(card).contains("if (approval.budget && budgetChanged(approval.budget)) {\n"
                + "    card.appendChild(el(\"p\", \"Proposed under your earlier budget of \"\n"
                + "        + money(approval.budget.amount_minor, approval.budget.currency), \"budget-line\"));\n"
                + "  } else if (linked && approval.budget) {");
        assertThat(card.replace("approve.disabled = left === 0;", "")).as("only the expiry disables Approve")
                .doesNotContain("approve.disabled");
        String budget = block(js, "budget");
        assertThat(budget).contains("function budgetChanged(budget) {\n"
                + "  return Boolean(currentRun) && !(runBudget && runBudget.amount_minor === budget.amount_minor\n"
                + "      && runBudget.currency === budget.currency);\n"
                + "}");
    }

    // Covers: 24.3, P9 (the pre-fill reads an amount with a limit word from the message, with a $, € or £ sign or a code)
    @Test
    void budgetPrefill_knowsTheLimitWords_andTheCurrencySigns() throws IOException {
        String budget = block(read("console.js"), "budget");

        assertThat(budget).contains("function budgetFromMessage(text) {");
        for (String word : List.of("under", "below", "max", "maximum", "no more than", "at most", "less than", "up to",
                "or less", "budget", "$", "€", "£", "₹", "rs", "rupees", "USD", "EUR", "GBP", "INR")) {
            assertThat(budget).as("the pre-fill knows " + word).contains(word);
        }
    }

    // Covers: 24.3, P9 (the pre-fill only fills the field, visibly, while the user has not touched it; once the user edits
    // or clears the field, or changes its currency, typing never fills it again; on a follow-up it only ever fills the
    // field, so a follow-up without an amount never changes the budget)
    @Test
    void budgetPrefill_onlyFillsAnUntouchedField_andAnEditedFieldWins() throws IOException {
        String js = read("console.js");
        String budget = block(js, "budget");

        assertThat(budget).contains("let budgetTouched = false;")
                .contains("function onMessageInput() {\n"
                        + "  if (budgetTouched) {\n"
                        + "    return;\n"
                        + "  }\n"
                        + "  const found = budgetFromMessage($(\"message\").value);\n"
                        + "  if (found) {\n"
                        + "    $(\"budget\").value = found.amount;\n"
                        + "    showCurrency(found.currency);\n"
                        + "  } else if (!continuingRun()) {\n"
                        + "    $(\"budget\").value = \"\";\n"
                        + "  }\n"
                        + "}");
        assertThat(js).contains("$(\"message\").addEventListener(\"input\", onMessageInput);")
                .contains("$(\"budget\").addEventListener(\"input\", () => {\n    budgetTouched = true;\n  });")
                .contains("$(\"budget-currency\").addEventListener(\"change\", () => {\n    budgetTouched = true;\n  });");
        assertThat(js.replace("let budgetTouched = false;", "").replace("budgetTouched = true;", ""))
                .as("nothing else sets the flag, so a pre-fill never counts as the user's edit")
                .doesNotContain("budgetTouched = ");
    }

    // Covers: 24.3, 23.1, P9 (only the field is sent: the send handler never parses the message; a new run sends it with
    // the message; a continuation sends the message alone, after a PATCH when the field differs from the run's budget)
    @Test
    void send_sendsOnlyTheField_onANewRunOnly() throws IOException {
        String js = read("console.js");
        String send = js.substring(js.indexOf("async function send(event) {"), js.indexOf("function showRun(run) {"));
        String budget = block(js, "budget");

        assertThat(send).doesNotContain("budgetFromMessage")
                .contains("const budget = budgetToSend();")
                .contains("? await api(\"POST\", \"/api/runs/\" + currentRun.id + \"/messages\", {message: text})")
                .contains(": await api(\"POST\", \"/api/runs\", Object.assign({message: text}, budget, shipTo));");
        String toSend = budget.substring(budget.indexOf("function budgetToSend() {"));
        toSend = toSend.substring(0, toSend.indexOf("\n}\n") + 2);
        assertThat(toSend).contains("$(\"budget\").value").contains("$(\"budget-currency\").value")
                .contains("budget_minor").contains("currency").doesNotContain("message");
        assertThat(js).doesNotContain("$(\"budget\").disabled = ").doesNotContain("$(\"budget-currency\").disabled = ");
    }

    // Covers: 24.3, 23.2, P9, I2 (the card shows the user's budget from the approval answer's budget field only, under
    // the total; at a handoff merchant it says the budget is before the store's shipping and tax)
    @Test
    void approvalCard_showsTheBudget_fromTheAnswerOnly() throws IOException {
        String card = card(read("console.js"));

        assertThat(card).contains("if (approval.budget) {\n"
                + "    card.appendChild(el(\"p\", \"Your budget: \" + money(approval.budget.amount_minor, approval.budget.currency)\n"
                + "        + (approval.completion === \"handoff\" ? \" (before the store's shipping and tax)\" : \"\"), \"budget-line\"));\n"
                + "  }");
        assertThat(card.indexOf("if (approval.budget) {")).as("under the total row")
                .isGreaterThan(card.indexOf("card.appendChild(table);"))
                .isLessThan(card.indexOf("const info = el(\"div\", null, \"info\");"));
    }

    /** 24.3's run-end table: each status line and chat text, in the page's own words. */
    private static final List<String> RUN_END_TEXTS = List.of(
            "Working on it.", "Waiting for your answer.", "Waiting for your approval.", "Done.",
            "Stopped: ran out of steps.",
            "I looked at several options but ran out of steps before choosing. Try a narrower request.",
            "Stopped: ran out of time.", "I ran out of time before choosing. Try a narrower request.",
            "Stopped: too much to read.", "I had too much to read before choosing. Try a narrower request.",
            "Stopped: no answer from you.", "I stopped waiting for your answer. Send a new message to start again.",
            "Stopped: the model could not be reached.", "I couldn't reach the model, so I stopped. Try again in a moment.",
            "Stopped: the model is busy.", "The model is busy right now; try again in a minute.",
            "Stopped: a step failed.", "A step failed, so I stopped. Try again in a moment.",
            "Stopped: something went wrong.", "Something went wrong on our side, so I stopped. Try again in a moment.",
            "Stopped: the service restarted.",
            "The service restarted while I was working, so I stopped. Send your request again.",
            "Stopped.", "I stopped before finishing. Try again in a moment.");

    // Covers: 24.3, 23 (the status line names every run status and reason in words, never the codes: the live check
    // showed "Run budget exceeded (TOOL_CALLS)."; each of 24.3's texts is in the page, and each limit and reason code
    // is only a key of its table)
    @Test
    void runEnd_theStatusLineNamesEveryStatusAndReasonInWords_neverTheCode() throws IOException {
        String js = read("console.js");
        String end = block(js, "run-end");

        assertThat(end).as("24.3's run-end table").isNotEmpty();
        for (String text : RUN_END_TEXTS) {
            assertThat(end).as(text).contains("\"" + text + "\"");
        }
        for (String code : List.of("MODEL_CALLS", "TOOL_CALLS", "WALL_CLOCK", "TOKENS", "model_error", "tool_error",
                "internal_error", "interrupted", "budget_exceeded", "user_timeout", "failed", "running",
                "waiting_for_user", "done")) {
            assertThat(end).as(code).contains("\"" + code + "\"");
        }
        assertThat(js).as("the codes are never shown").doesNotContain("run.status.replace(")
                .doesNotContain("run.reason ? \" (\"");
        assertThat(count(js, "run.reason")).as("the reason is read only by the table").isEqualTo(count(end, "run.reason"));
        assertThat(end).doesNotContain("innerHTML").doesNotContain("textContent");
    }

    // Covers: 24.3, 6.3, 6.5 (a model_error whose cause is http_429 or http_503 reads "the model is busy", in the
    // status line and the chat; any other cause keeps the model_error row; the cause is read only by the table)
    @Test
    void runEnd_aBusyModel_saysSo_fromTheCause() throws IOException {
        String js = read("console.js");
        String end = block(js, "run-end");

        assertThat(end).contains("const BUSY = [\"Stopped: the model is busy.\",")
                .contains("\"The model is busy right now; try again in a minute.\"]")
                .contains("const BUSY_CAUSES = new Set([\"http_429\", \"http_503\"]);")
                .contains("if (run.status === \"failed\" && run.reason === \"model_error\" && BUSY_CAUSES.has(run.cause)) {");
        assertThat(end.indexOf("BUSY_CAUSES.has(run.cause)")).as("before the table's own rows")
                .isLessThan(end.indexOf("const ends = RUN_ENDS.get(run.status);"));
        assertThat(count(js, "run.cause")).as("the cause is read only by the table").isEqualTo(count(end, "run.cause"));
    }

    // Covers: 24.3, 23.3 (the order counters and the fault lab's selector list only the merchants whose lab is true;
    // with none, as in catalog mode, nothing polls GET /api/lab/orders, which is 422 for any other merchant)
    @Test
    void orderCounters_pollOnlyTheLabsMerchants_andNothingWithoutOne() throws IOException {
        String js = read("console.js");
        String start = js.substring(js.indexOf("async function startLab() {"), js.indexOf("async function refreshOrders() {"));

        assertThat(start).contains("const lab = list.data.merchants.filter((m) => m.lab === true);")
                .contains("lab.forEach((m) => {")
                .doesNotContain("list.data.merchants.forEach(")
                .contains("  if (lab.length > 0) {\n    refreshOrders();\n    window.setInterval(refreshOrders, 2000);\n  }");
        assertThat(count(js, "/api/lab/orders")).as("only refreshOrders reads the orders").isEqualTo(1);
        assertThat(count(js, "setInterval(refreshOrders")).isEqualTo(1);
    }

    // Covers: 24.3, 6.3 (a run that ended in budget_exceeded, user_timeout or failed gets the table's chat text as the
    // agent's last bubble, after the run's messages, set as text; the status line takes the table's text)
    @Test
    void runEnd_aRunThatEndedWithoutAnAnswer_saysSoInTheChat() throws IOException {
        String js = read("console.js");
        String show = js.substring(js.indexOf("function showRun(run) {"), js.indexOf("async function pollRun() {"));

        assertThat(show).contains("const [statusText, chatText] = runEnd(run);")
                .contains("if (chatText) {")
                .contains("$(\"chat\").appendChild(el(\"li\", chatText, \"agent\"));")
                .contains("setStatus(statusText);");
        assertThat(show.indexOf("(run.messages || []).forEach")).as("after the run's messages")
                .isLessThan(show.indexOf("if (chatText) {"));
        String end = block(js, "run-end");
        assertThat(end).contains("[\"budget_exceeded\",").contains("[\"user_timeout\",").contains("[\"failed\",");
    }

    // Covers: 24.3, 26.7 (in catalog mode the badge reads "Shopify Global Catalog", not "Lab merchants", and Developer
    // tools stay hidden: from the followed intent's merchant.completion, or before one from the lab list's completion;
    // a live store's badge comes first)
    @Test
    void merchantMode_inCatalogMode_readsShopifyGlobalCatalog_andHidesDeveloperTools() throws IOException {
        String mode = block(read("console.js"), "merchant-mode");

        assertThat(mode).contains("currentIntent.merchant.completion === \"link\"")
                .contains("labMerchants.some((m) => m.completion === \"link\")")
                .contains("if (!domain && catalogMode()) {")
                .contains("badge.textContent = \"Shopify Global Catalog\";")
                .contains("$(\"dev-tools\").hidden = true;")
                .contains("$(\"dev-tools\").open = false;");
        assertThat(mode.indexOf("badge.textContent = domain ? \"Live store · \" + domain : \"Lab merchants\";"))
                .as("the catalog's text replaces the lab's, after it").isLessThan(mode.indexOf("if (!domain && catalogMode()) {"));
        assertThat(count(mode, "Shopify Global Catalog")).isEqualTo(1);
    }

    /** The code of function {@code name} in console.js, up to the next top-level function. */
    private static String function(String js, String name) {
        int from = js.indexOf("function " + name + "(");
        assertThat(from).as("function " + name).isNotNegative();
        int to = js.indexOf("\n}\n", from);
        return js.substring(from, to + 2);
    }

    // Covers: 24.3 (on Send the button is disabled and reads "Working…", and under the user's message an agent bubble
    // with a working indicator reads "Thinking…"; a second Send while it works sends nothing)
    @Test
    void working_onSend_disablesSendAsWorking_andShowsAThinkingBubble() throws IOException {
        String js = read("console.js");
        String working = block(js, "working");
        String start = function(working, "startWorking");
        String send = function(js, "send");

        assertThat(read("index.html")).contains("<button type=\"submit\" class=\"primary\" id=\"send-button\">Send</button>");
        assertThat(working).contains("const THINKING = \"Thinking…\";");
        assertThat(start).contains("button.disabled = true;").contains("button.textContent = \"Working…\";")
                .contains("el(\"li\", null, \"agent working\")").contains("el(\"span\", THINKING, \"working-step\")")
                .contains("$(\"chat\").appendChild(bubble);");
        assertThat(send).contains("  if (working) {\n    return;\n  }")
                .contains("$(\"chat\").appendChild(el(\"li\", text, \"user\"));");
        assertThat(send.indexOf("$(\"chat\").appendChild(el(\"li\", text, \"user\"));")).as("the message, then the bubble")
                .isLessThan(send.indexOf("startWorking();"));
        assertThat(send.indexOf("startWorking();")).as("before the request, which waits for a settle point")
                .isLessThan(send.indexOf("await api("));
    }

    // Covers: 24.3 (each tool's step is a fixed text of the page, looked up by the tool's name)
    @ParameterizedTest
    @CsvSource({
            "search_catalog, Searching the catalog…",
            "propose_purchase, Preparing your approval…",
            "get_purchase_status, Checking the purchase…"})
    void working_eachToolHasItsStep(String tool, String text) throws IOException {
        assertThat(block(read("console.js"), "working")).contains("[\"" + tool + "\", \"" + text + "\"]");
    }

    // Covers: 24.3, I2, S10 (the bubble's step comes from the run's tool events only: the newest event's tool name picks
    // a fixed text, get_product names the one non-link merchant of the lab list, any other tool reads "Thinking…"; the
    // step's code never reads a message, the model's text or an event's summary, and sets text only)
    @Test
    void working_theStepComesFromToolEventsOnly_neverTheModelsWords() throws IOException {
        String js = read("console.js");
        String working = block(js, "working");
        String step = function(working, "stepText");
        String price = function(working, "priceStep");

        assertThat(step).contains("const newest = events.length > 0 ? events[events.length - 1].tool : \"\";")
                .contains("if (newest === \"get_product\") {").contains("return STEP_TEXTS.get(newest) || THINKING;");
        assertThat(price).contains("const only = labMerchants.length === 1 ? labMerchants[0] : null;")
                .contains("only && only.completion !== \"link\" && only.name")
                .contains("\"Checking the price at \" + only.name + \"…\"").contains("\"Checking the price…\"");
        assertThat(function(working, "showWorking")).contains("working.step.textContent = stepText(run.tool_events || []);");
        for (String other : List.of("messages", "summary", "rationale", "description", "innerHTML", "run.reason",
                "run.cause")) {
            assertThat(working).as("the working block reads " + other).doesNotContain(other);
        }
        assertThat(working).as("a text field (textContent is only written)").doesNotContainPattern("\\.text\\b");
    }

    // Covers: 24.3 (each tool call's chip appears while the run works: a poll that carries tool events shows them, with
    // the same chips as at the end)
    @Test
    void working_toolChipsAppearLive() throws IOException {
        String js = read("console.js");
        String show = function(js, "showRun");

        assertThat(show).contains("if (run.tool_events) {\n    showChips(run.tool_events);\n  }");
        assertThat(function(js, "loadRunDetails")).contains("showChips(result.data.tool_events || []);");
        assertThat(count(js, "el(\"span\", null, e.summary === \"ok\" ? \"dot ok\" : \"dot warn\")")).as("one chip maker")
                .isEqualTo(1);
        assertThat(function(js, "showChips")).contains("clear($(\"tools\"));");
    }

    // Covers: 24.3 (from 10 s after Send the bubble adds "Still working · <n> s", whole seconds by the page's clock,
    // updated every second)
    @Test
    void working_after10s_addsTheElapsedTime() throws IOException {
        String working = block(read("console.js"), "working");

        assertThat(working).contains("const STILL_AFTER_MS = 10000;")
                .contains("timer: window.setInterval(tickWorking, 1000)");
        assertThat(function(working, "tickWorking")).contains("const ms = Date.now() - working.since;")
                .contains("ms >= STILL_AFTER_MS ? \"Still working · \" + Math.floor(ms / 1000) + \" s\" : \"\"");
    }

    // Covers: 24.3 (when the run leaves running the bubble is removed and Send is enabled and reads Send again; a failed
    // request or poll does the same and puts its error on the status line)
    @Test
    void working_onRunEnd_orAnError_removesTheBubble_andEnablesSend() throws IOException {
        String js = read("console.js");
        String stop = function(block(js, "working"), "stopWorking");
        String show = function(js, "showRun");

        assertThat(stop).contains("window.clearInterval(working.timer);").contains("working.bubble.remove();")
                .contains("working = null;").contains("button.disabled = false;").contains("button.textContent = \"Send\";");
        assertThat(show).contains("  if (run.status === \"running\") {\n    showWorking(run);\n  } else {\n    stopWorking();\n    setStatus(statusText);\n  }");
        assertThat(show.indexOf("if (chatText) {")).as("the run-end text, then the bubble").isLessThan(show.indexOf("showWorking(run);"));
        assertThat(function(js, "send")).contains("  if (!result.ok) {\n    stopWorking();\n    setStatus(errorText(result), true);\n    return;\n  }");
        assertThat(function(js, "pollRun")).contains("  if (!result.ok) {\n    stopWorking();\n    setStatus(errorText(result), true);\n    return;\n  }");
    }

    // Covers: 24.3 (the status line says "Working on it." once, when the work starts, never on a poll or a step; the
    // bubble is outside the aria-live region)
    @Test
    void working_theStatusLineSaysWorkingOnce_andTheBubbleIsNotAnnounced() throws IOException {
        String js = read("console.js");
        String html = read("index.html");

        assertThat(count(js, "setStatus(\"Working on it.\")")).isEqualTo(1);
        assertThat(function(block(js, "working"), "startWorking")).contains("setStatus(\"Working on it.\");");
        assertThat(function(block(js, "working"), "showWorking")).doesNotContain("setStatus(");
        assertThat(count(function(js, "showRun"), "setStatus(")).as("only for a run that left running").isEqualTo(1);
        String chat = html.substring(html.indexOf("<ol id=\"chat\""), html.indexOf(">", html.indexOf("<ol id=\"chat\"")));
        assertThat(chat).doesNotContain("aria-live").doesNotContain("role=\"status\"");
    }

    // Covers: 24.3 (the indicator's dots are its only animation, hidden from screen readers, and under
    // prefers-reduced-motion: reduce they are gone, leaving the text)
    @Test
    void working_underReducedMotion_isTextOnly() throws IOException {
        String css = read("console.css");
        String start = function(block(read("console.js"), "working"), "startWorking");

        assertThat(start).contains("dots.setAttribute(\"aria-hidden\", \"true\");");
        assertThat(css).contains(".working-dots::before {").contains("animation: working-pulse 1.2s ease-in-out infinite;")
                .contains("@keyframes working-pulse {");
        assertThat(count(css, "animation:")).as("one animation").isEqualTo(1);
        int reduced = css.indexOf("@media (prefers-reduced-motion: reduce) {");
        assertThat(reduced).isPositive();
        assertThat(css.substring(reduced, css.indexOf("\n}\n", reduced))).contains(".working-dots { display: none; }");
    }
}
