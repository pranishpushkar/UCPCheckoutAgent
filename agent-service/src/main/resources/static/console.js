// The UCP Checkout Lab console (24.1 to 24.3). Plain JavaScript, no framework, no build step. Every piece of data goes
// into the page with textContent through el(); there is no way in this file to write markup from data (24.3). The
// session is the cookie; the CSRF token and the approval token live in variables of this page only.
"use strict";

const TERMINAL = ["COMPLETED", "HANDED_OFF", "REJECTED", "FAILED_NOT_CHARGED", "ESCALATED"];
const RUN_WAITING = ["waiting_for_user", "done", "waiting_for_approval"];

let csrf = "";
let currentRun = null;      // {id, status, intents}
let currentIntent = null;   // {id, state}
let intentTimer = null;
let runTimer = null;
const countdowns = new Map();   // intent id -> its card's expiry timer
const counters = new Map();    // merchant id -> its order counter element
let labMerchants = [];         // the lab's merchant list, where the profile has one (23.3)
let labAvailable = false;

const $ = (id) => document.getElementById(id);

function el(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined && text !== null) {
    node.textContent = String(text);
  }
  if (className) {
    node.className = className;
  }
  return node;
}

function clear(node) {
  while (node.firstChild) {
    node.removeChild(node.firstChild);
  }
}

// Status changes are announced through the one aria-live region.
function setStatus(text, isError) {
  const node = $("status");
  node.textContent = text;
  node.className = isError ? "status error" : "status";
}

function money(minor, currency) {
  const amount = (Number(minor) / 100).toFixed(2);
  return (currency === "USD" || !currency ? "$" : currency + " ") + amount;
}

// One request. A 401 goes to the sign-in page, as 24.3 says; every other error comes back as {code, message}.
async function api(method, path, body) {
  const headers = {"Accept": "application/json"};
  if (method !== "GET") {
    headers["Content-Type"] = "application/json";
    headers["X-CSRF-Token"] = csrf;
  }
  const response = await fetch(path, {
    method: method,
    headers: headers,
    credentials: "same-origin",
    cache: "no-store",
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  let data = null;
  const text = await response.text();
  if (text) {
    try {
      data = JSON.parse(text);
    } catch (e) {
      data = null;
    }
  }
  if (response.status === 401 && !(data && data.code === "reauth_required")) {
    window.location.assign("/auth/login");
  }
  return {status: response.status, ok: response.ok, data: data, headers: response.headers};
}

function errorText(result) {
  return result.data && result.data.message ? result.data.message : "Request failed (" + result.status + ").";
}

// <theme>
// 24.3: light by default, whatever the system's setting; dark only after the header's toggle. The choice is the one
// value the console keeps in browser storage, under one key; empty, blocked or anything else is light.
const THEME_KEY = "ucplab.theme";

function storedTheme() {
  try {
    return localStorage.getItem(THEME_KEY) === "dark" ? "dark" : "light";
  } catch (e) {
    return "light";
  }
}

function applyTheme(theme) {
  document.documentElement.setAttribute("data-theme", theme);
  $("theme-toggle").setAttribute("aria-pressed", String(theme === "dark"));
}

function toggleTheme() {
  const theme = document.documentElement.getAttribute("data-theme") === "dark" ? "light" : "dark";
  applyTheme(theme);
  try {
    localStorage.setItem(THEME_KEY, theme);
  } catch (e) {
    // Storage is blocked: the choice lasts for this page only.
  }
}

function startTheme() {
  applyTheme(storedTheme());
  $("theme-toggle").addEventListener("click", toggleTheme);
}
// </theme>

// ---- boot ----------------------------------------------------------------------------------------------------

async function boot() {
  startTheme();
  const me = await api("GET", "/api/me");
  if (!me.ok) {
    return;
  }
  csrf = me.headers.get("X-CSRF-Token") || "";
  $("who").textContent = me.data.email;
  $("sign-out").hidden = false;
  $("sign-out").addEventListener("click", signOut);
  $("send").addEventListener("submit", send);
  $("message").addEventListener("input", onMessageInput);
  $("budget").addEventListener("input", () => {
    budgetTouched = true;
  });
  $("budget-currency").addEventListener("change", () => {
    budgetTouched = true;
  });
  document.querySelectorAll("[data-preset]").forEach((button) => {
    button.addEventListener("click", () => preset(button.getAttribute("data-preset")));
  });
  $("clear-faults").addEventListener("click", clearFaults);
  $("reset-lab").addEventListener("click", resetLab);
  await startLab();
  const fromPath = window.location.pathname.match(/^\/intents\/([A-Za-z0-9_-]{1,64})$/);
  if (fromPath) {
    // Back from a fresh sign-in: show the intent and its approval, so the person can decide again.
    watchIntent(fromPath[1]);
    loadApproval(fromPath[1]);
    setStatus("Signed in again. Review the approval and decide.");
  } else {
    setStatus("Ready.");
  }
}

async function signOut() {
  await api("POST", "/auth/logout", {});
  window.location.assign("/auth/login");
}

// ---- the agent panel --------------------------------------------------------------------------------------------

// <budget>
// 24.3: the optional Budget field. As the user types a first message, the console fills the field, visibly, from an
// amount with a limit word, until the user edits or clears the field or changes its currency. Only what the field holds
// when the user presses Send is sent (23.1); the server never parses the message, and neither does send().
let budgetTouched = false;

const BUDGET_AMOUNT = "([$€£]|USD|EUR|GBP)?\\s*((?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,2})?)\\s*(USD|EUR|GBP)?";
const BUDGET_LIMIT = new RegExp(
    "\\b(?:under|below|maximum|max|no more than|at most|less than)\\s+" + BUDGET_AMOUNT
    + "|" + BUDGET_AMOUNT + "\\s+or less\\b", "i");
const BUDGET_SIGNS = {"$": "USD", "€": "EUR", "£": "GBP"};

// An amount with a limit word and a sign or code ("under $500", "max 80 EUR", "£40 or less"), or null.
function budgetFromMessage(text) {
  const m = BUDGET_LIMIT.exec(text);
  if (!m) {
    return null;
  }
  const sign = m[1] || m[3] || m[4] || m[6];
  const amount = m[2] || m[5];
  if (!sign) {
    return null;
  }
  return {amount: amount.replace(/,/g, ""), currency: BUDGET_SIGNS[sign] || sign.toUpperCase()};
}

function continuingRun() {
  return Boolean(currentRun && RUN_WAITING.includes(currentRun.status));
}

function onMessageInput() {
  if (budgetTouched || continuingRun()) {
    return;
  }
  const found = budgetFromMessage($("message").value);
  $("budget").value = found ? found.amount : "";
  if (found) {
    $("budget-currency").value = found.currency;
  }
}

// The field as budget_minor and currency: {} when it is empty, null when it is not an amount of at least 0.01.
function budgetToSend() {
  const raw = $("budget").value.trim().replace(/,/g, "");
  if (raw === "") {
    return {};
  }
  const m = /^(\d{1,10})(?:\.(\d{1,2}))?$/.exec(raw);
  if (!m) {
    return null;
  }
  const minor = Number(m[1]) * 100 + Number((m[2] || "0").padEnd(2, "0"));
  if (minor < 1) {
    return null;
  }
  return {budget_minor: minor, currency: $("budget-currency").value};
}

// While a run continues, the field keeps the run's budget and cannot change: the budget is fixed for the run.
function showBudgetField() {
  $("budget").disabled = continuingRun();
  $("budget-currency").disabled = continuingRun();
}
// </budget>

async function send(event) {
  event.preventDefault();
  const input = $("message");
  const text = input.value.trim();
  if (!text) {
    return;
  }
  const continuing = continuingRun();
  const budget = continuing ? {} : budgetToSend();
  if (budget === null) {
    setStatus("Enter the budget as an amount such as 500 or 499.99, or leave it empty.", true);
    return;
  }
  input.value = "";
  const result = continuing
      ? await api("POST", "/api/runs/" + currentRun.id + "/messages", {message: text})
      : await api("POST", "/api/runs", Object.assign({message: text}, budget));
  if (!result.ok) {
    setStatus(errorText(result), true);
    return;
  }
  showRun(result.data);
}

function showRun(run) {
  currentRun = {id: run.run_id, status: run.status, intents: run.intent_ids || []};
  clear($("chat"));
  (run.messages || []).forEach((m) => {
    const item = el("li", m.text, m.role + (m.kind === "question" ? " question" : ""));
    $("chat").appendChild(item);
  });
  setStatus("Run " + run.status.replace(/_/g, " ") + (run.reason ? " (" + run.reason + ")" : "") + ".");
  const intents = run.intent_ids || [];
  if (intents.length > 0) {
    const newest = intents[intents.length - 1];
    if (!currentIntent || currentIntent.id !== newest) {
      watchIntent(newest);
    }
  }
  window.clearTimeout(runTimer);
  if (run.status === "running") {
    runTimer = window.setTimeout(pollRun, 1000);
  } else {
    loadRunDetails();
    // Every intent of the run that awaits approval gets its own card, each headed by its own merchant (S22).
    if (run.status === "waiting_for_approval") {
      intents.forEach(loadApproval);
    }
  }
  showOtherIntents(intents);
  showBudgetField();
}

async function pollRun() {
  if (!currentRun) {
    return;
  }
  const result = await api("GET", "/api/runs/" + currentRun.id);
  if (result.ok) {
    showRun(result.data);
  }
}

async function loadRunDetails() {
  if (!currentRun) {
    return;
  }
  const result = await api("GET", "/api/runs/" + currentRun.id);
  if (!result.ok) {
    return;
  }
  clear($("tools"));
  // 24.3: one chip per call, with a dot for its outcome; a summary other than ok is also shown as text.
  (result.data.tool_events || []).forEach((e) => {
    const chip = el("li", null, "chip");
    chip.appendChild(el("span", null, e.summary === "ok" ? "dot ok" : "dot warn")).setAttribute("aria-hidden", "true");
    chip.appendChild(el("span", e.tool + " · " + e.took_ms + " ms" + (e.summary === "ok" ? "" : " · " + e.summary)));
    $("tools").appendChild(chip);
  });
}

// ---- the approval card ---------------------------------------------------------------------------------------------
// The card is a separate component fed only by the approval endpoint (9.3 L4, I2): never by the chat, never by the run's
// messages. It reads the snapshot's lines, currency and total, the expiry and the token, and nothing else.

// <approval-card>
// One card per intent awaiting approval, each built from that intent's own approval response.
async function loadApproval(id) {
  const approvalResponse = await api("GET", "/api/intents/" + id + "/approval");
  if (!approvalResponse.ok) {
    hideApprovalCard(id);
    return;
  }
  showApprovalCard(id, approvalResponse);
}

function hideApprovalCard(id) {
  window.clearInterval(countdowns.get(id));
  countdowns.delete(id);
  document.querySelectorAll("#approval-cards [data-intent]").forEach((node) => {
    if (node.getAttribute("data-intent") === id) {
      node.remove();
    }
  });
}

// 24.3, 19: the card's last lines, each [label, minor units], the total last. At a handoff merchant the store adds
// shipping and tax after the handoff, so the quote has none and there is no tax line.
function cardCharges(snapshot, handoff, linesTotal) {
  const rows = [];
  const other = snapshot.total_minor - linesTotal;
  if (!handoff) {
    rows.push(["Other charges (tax)", other]);
  } else if (other !== 0) {
    rows.push(["Other charges", other]);
  }
  rows.push([handoff ? "Total before the store's shipping and tax" : "Total", snapshot.total_minor]);
  return rows;
}

function showApprovalCard(id, approvalResponse) {
  const approval = approvalResponse.data;
  const snapshot = approval.snapshot;
  const token = approval.token;       // kept in this closure only; never rendered, never stored
  hideApprovalCard(id);
  const card = el("section", null, "card");
  card.setAttribute("data-intent", id);
  card.setAttribute("aria-label", "Approval from " + snapshot.merchant_id);
  const head = el("div", null, "card-head");
  const heading = el("div");
  heading.appendChild(el("h3", "Approval needed"));
  heading.appendChild(el("p", "From " + snapshot.merchant_id, "muted"));
  head.appendChild(heading);
  const expiry = el("span", null, "pill");
  head.appendChild(expiry);
  card.appendChild(head);
  const table = el("table");
  const columns = el("tr");
  ["Item", "Qty × price", "Line total"].forEach((label, i) => {
    const th = el("th", label, i === 0 ? null : "num");
    th.scope = "col";
    columns.appendChild(th);
  });
  const top = el("thead");
  top.appendChild(columns);
  table.appendChild(top);
  const body = el("tbody");
  const foot = el("tfoot");
  let linesTotal = 0;
  snapshot.lines.forEach((line) => {
    const row = el("tr");
    const name = el("td");
    if (line.title) {
      name.appendChild(el("div", line.title));
    }
    name.appendChild(el("div", line.item_id, "item-id"));
    row.appendChild(name);
    row.appendChild(el("td", line.quantity + " × " + money(line.unit_price_minor, snapshot.currency), "num"));
    row.appendChild(el("td", money(line.quantity * line.unit_price_minor, snapshot.currency), "num"));
    body.appendChild(row);
    linesTotal += line.quantity * line.unit_price_minor;
  });
  const rows = cardCharges(snapshot, approval.completion === "handoff", linesTotal);
  rows.forEach(([label, minor], i) => {
    const last = i === rows.length - 1;
    const row = el("tr", null, last ? "total" : null);
    const name = el(last ? "th" : "td", label);
    name.colSpan = 2;
    if (last) {
      name.scope = "row";
    }
    row.appendChild(name);
    row.appendChild(el("td", money(minor, snapshot.currency), "num"));
    foot.appendChild(row);
  });
  table.appendChild(body);
  table.appendChild(foot);
  card.appendChild(table);
  if (approval.budget) {
    card.appendChild(el("p", "Your budget: " + money(approval.budget.amount_minor, approval.budget.currency)
        + (approval.completion === "handoff" ? " (before the store's shipping and tax)" : ""), "budget-line"));
  }
  const info = el("div", null, "info");
  info.appendChild(el("p", "Price and stock are not held."));
  if (approval.completion === "handoff") {
    info.appendChild(el("p", approval.note));
  }
  card.appendChild(info);
  const actions = el("div", null, "actions");
  const approve = el("button", "Approve " + money(snapshot.total_minor, snapshot.currency), "primary");
  approve.type = "button";
  const reject = el("button", "Reject");
  reject.type = "button";
  actions.appendChild(approve);
  actions.appendChild(reject);
  card.appendChild(actions);
  $("approval-cards").appendChild(card);

  const expiresAt = Date.parse(approval.expires_at);
  const tick = () => {
    const left = Math.max(0, Math.round((expiresAt - Date.now()) / 1000));
    expiry.textContent = left > 0
        ? "Expires in " + Math.floor(left / 60) + ":" + String(left % 60).padStart(2, "0")
        : "Expired. Ask for a new quote.";
    approve.disabled = left === 0;
  };
  tick();
  countdowns.set(id, window.setInterval(tick, 1000));

  approve.onclick = () => decide(id, token, "approve");
  reject.onclick = () => decide(id, token, "reject");
}
// </approval-card>

async function decide(id, token, decision) {
  const buttons = document.querySelectorAll("#approval-cards [data-intent=\"" + id + "\"] button");
  buttons.forEach((b) => { b.disabled = true; });
  const result = await api("POST", "/api/intents/" + id + "/approval", {token: token, decision: decision});
  buttons.forEach((b) => { b.disabled = false; });
  if (result.status === 401 && result.data && result.data.code === "reauth_required") {
    setStatus("Signing in again to approve.");
    window.location.assign("/auth/login?reauth=true&return_to=/intents/" + id);
    return;
  }
  if (!result.ok) {
    setStatus(errorText(result), true);
    loadApproval(id);
    return;
  }
  hideApprovalCard(id);
  setStatus(decision === "approve" ? "Approved. Completing the purchase." : "Rejected.");
  watchIntent(id);
}

// ---- the timeline ------------------------------------------------------------------------------------------------------

function watchIntent(id) {
  clear($("handoff"));
  clear($("follow-up"));
  clear($("steps"));
  currentIntent = {id: id, state: ""};
  window.clearTimeout(intentTimer);
  pollIntent();
}

async function pollIntent() {
  if (!currentIntent) {
    return;
  }
  const id = currentIntent.id;
  const result = await api("GET", "/api/intents/" + id);
  if (!currentIntent || currentIntent.id !== id) {
    return;
  }
  if (result.status === 404) {
    // Not the caller's intent, or no such intent: the API does not say which (T6), and neither does the page.
    clear($("timeline"));
    clear($("steps"));
    $("timeline-state").textContent = "Intent not found.";
    $("all-events").hidden = true;
    setStatus("Intent not found.", true);
    currentIntent = null;
    showMerchantMode();
    return;
  }
  if (!result.ok) {
    return;
  }
  const state = result.data.state;
  currentIntent.merchant = result.data.merchant;
  showMerchantMode();
  showSteps(milestones(result.data));
  if (state !== currentIntent.state) {
    currentIntent.state = state;
    $("timeline-state").textContent = "Intent " + id;
    setStatus("Intent " + state.replace(/_/g, " ").toLowerCase() + ".");
    await loadEvents(id);
    if (state !== "HANDED_OFF") {
      clear($("handoff"));
      clear($("follow-up"));
    }
    if (state === "AWAITING_APPROVAL") {
      loadApproval(id);
    }
  }
  // 24.3, 7.6: what the store reported, on every poll, since it changes while the state stays HANDED_OFF.
  if (state === "HANDED_OFF") {
    showSuccess(result.data.store_order ? followUpText(result.data.store_order, result.data.follow_up) : "",
        result.data.quote);
    if (keepsHandoffLink(result.data.store_order, result.data.follow_up)) {
      showHandoffLink(result.data.handoff_url);
    } else {
      clear($("handoff"));
    }
  } else if (state === "COMPLETED") {
    showSuccess("Order placed at the store", result.data.quote);
  }
  if (currentRun) {
    showOtherIntents(currentRun.intents);
  }
  if (!TERMINAL.includes(state)) {
    intentTimer = window.setTimeout(pollIntent, 1000);
  } else if (state === "HANDED_OFF" && result.data.follow_up && result.data.follow_up.watching) {
    intentTimer = window.setTimeout(pollIntent, 10000);
  }
}

// <handoff-link>
// 24.3: in HANDED_OFF only, the owner's way to finish at the store. The address is set as the link's target alone:
// never as text, in the page's address, the browser console, storage or the live region; a new tab with no opener
// and no referrer.
function showHandoffLink(url) {
  const holder = $("handoff");
  clear(holder);
  if (typeof url !== "string" || !url.startsWith("https://")) {
    return;
  }
  const anchor = el("a", "Continue at the store");
  anchor.href = url;
  anchor.target = "_blank";
  anchor.rel = "noopener noreferrer";
  anchor.referrerPolicy = "no-referrer";
  holder.appendChild(anchor);
}
// </handoff-link>

// <follow-up>
// 24.3, 7.6: what the store reported after the handoff. A completed checkout means the store placed an order; it says
// nothing of payment, so neither does the text. The confirmation code (VERIFY 11) is set as text only. The text is the
// stepper's last step, and the store's order is also the success card.

// The text of a follow-up that ended without an order, by its ended_reason. A Map, so no other key can match.
const FOLLOW_UP_ENDED = new Map([
  ["window_ended", "The store reported no order within 24 h"],
  ["reads_exhausted", "The store reported no order within 24 h"],
  ["canceled", "The checkout was cancelled at the store; no order was placed"],
  ["session_gone", "The checkout is no longer available at the store"],
  ["merchant_changed", "Stopped checking: the store's settings changed"],
]);

// The endings after which the checkout may still be open, so the Continue at the store link stays.
const LINK_KEPT_WHEN_ENDED = ["window_ended", "reads_exhausted"];

// Whether the Continue at the store link stays: not once the store's order is shown, and after an ending only where the
// checkout may still be open.
function keepsHandoffLink(order, followUp) {
  if (order) {
    return false;
  }
  if (followUp && !followUp.watching) {
    return LINK_KEPT_WHEN_ENDED.includes(followUp.ended_reason);
  }
  return true;
}

// The follow-up's text: the store's order, waiting for one, or how the follow-up ended; empty when there is none.
function followUpText(order, followUp) {
  let text = "";
  if (order && typeof order.label === "string") {
    text = "Order placed at the store, confirmation #" + order.label;
  } else if (order) {
    text = "Order placed at the store";
  } else if (followUp && followUp.watching) {
    text = "Waiting for the store to report an order";
  } else if (followUp) {
    text = FOLLOW_UP_ENDED.get(followUp.ended_reason) || "";
  }
  return text;
}

// 24.3: the success card, in COMPLETED or once the store reported an order: its text, then the quote's product lines.
function showSuccess(text, quote) {
  const holder = $("follow-up");
  clear(holder);
  if (!text) {
    return;
  }
  const card = el("div", null, "success");
  card.appendChild(el("p", text, "success-title"));
  if (quote && Array.isArray(quote.lines)) {
    const list = el("ul", null, "success-lines");
    quote.lines.forEach((line) => {
      list.appendChild(el("li", (line.title || line.item_id) + " · " + line.quantity + " × "
          + money(line.unit_price_minor, quote.currency)));
    });
    card.appendChild(list);
  }
  holder.appendChild(card);
}
// </follow-up>

// <milestones>
// 24.3: the stepper, made from the intent's state, its merchant's completion, its approval and the store's follow-up;
// never from the name of anything the timeline records. Each step is {label, mark}: done, current, pending or ended.
// The state's text, for a reason REASON_TEXT does not name or no reason.
const ENDINGS = new Map([
  ["REJECTED", "Rejected: no order was placed"],
  ["FAILED_NOT_CHARGED", "Stopped: no order was placed"],
  ["ESCALATED", "Stopped: the outcome needs a person to check"],
]);

// 24.3: the text of each failure_reason. A Map, so no other key can match; the code itself is never shown.
const REASON_TEXT = new Map([
  ["approval_rejected", "Rejected by you"],
  ["approval_expired", "The approval expired: no order was placed"],
  ["handoff_timeout", "The handoff to the store timed out: no order was placed"],
  ["price_unstable", "The store's price kept changing: no order was placed"],
  ["policy_P1", "This store is not allowed: no order was placed"],
  ["policy_P2", "Above the limit for one purchase: no order was placed"],
  ["policy_P3", "Above your limit for the last 24 hours: no order was placed"],
  ["policy_P4", "Too many items or too large a quantity: no order was placed"],
  ["policy_P5", "Too many proposals in the last hour: no order was placed"],
  ["policy_I12", "The agent buys only at stores the operator owns: no order was placed"],
  ["out_of_stock", "Out of stock at the store: no order was placed"],
  ["item_unavailable", "The item is not available at the store: no order was placed"],
  ["quantity_adjusted", "The store changed the quantity: no order was placed"],
  ["quote_lines_differ", "The store's quote did not match the request: no order was placed"],
  ["quote_unsupported", "The store's checkout needs something the agent cannot provide: no order was placed"],
  ["fulfillment_unsupported", "The store offered no delivery the agent can choose: no order was placed"],
  ["quote_retries_exhausted", "The store did not answer with a quote: no order was placed"],
  ["create_rejected", "The store refused the checkout: no order was placed"],
  ["update_rejected", "The store refused the checkout: no order was placed"],
  ["payment_declined", "The payment was declined: no order was placed"],
  ["payment_invalid", "The payment was not accepted: no order was placed"],
  ["canceled_by_merchant", "The store cancelled the checkout: no order was placed"],
  ["complete_unavailable", "This store does not take orders from the agent: no order was placed"],
  ["session_gone", "The checkout is no longer available at the store: no order was placed"],
  ["continue_url_not_allowed", "The store's checkout link was not allowed: no order was placed"],
  ["outcome_unknown", "Stopped: the store did not confirm whether an order was placed; a person needs to check"],
  ["approval_invalid", "Stopped: the approval could not be confirmed; a person needs to check"],
  ["merchant_changed", "Stopped: the store's settings changed; a person needs to check"],
  ["merchant_not_owned", "Stopped: the store's settings changed; a person needs to check"],
  ["handoff_status_unexpected", "Stopped: the store reported an unexpected checkout status; a person needs to check"]
]);

// The two families of merchant codes, by prefix: the code after it is the store's, never shown.
const REASON_PREFIXES = [
  ["merchant_error_", "The store refused the checkout: no order was placed"],
  ["complete_error:", "The store refused the order: no order was placed"],
];

// P9: the total the store quoted and the user's budget, both from the intent answer (23.2), set as text.
function overBudgetText(intent) {
  const quoted = intent.refused_quote;
  const budget = intent.budget;
  if (!quoted || !budget) {
    return "Over your budget: no order was placed";
  }
  return "Over your budget: the store quoted " + money(quoted.total_minor, quoted.currency)
      + ", your budget is " + money(budget.amount_minor, budget.currency);
}

function endingText(intent) {
  const reason = typeof intent.failure_reason === "string" ? intent.failure_reason : "";
  if (reason === "policy_P9") {
    return overBudgetText(intent);
  }
  if (REASON_TEXT.has(reason)) {
    return REASON_TEXT.get(reason);
  }
  const prefixed = REASON_PREFIXES.find((p) => reason.startsWith(p[0]));
  return prefixed ? prefixed[1] : ENDINGS.get(intent.state);
}

// How far the intent got: 0 Proposed, 1 Quoted, 2 approval, 3 sent or handed off, 4 the store's order.
function reached(intent) {
  switch (intent.state) {
    case "PROPOSED": return 0;
    case "QUOTING": return 1;
    case "AWAITING_APPROVAL": case "REJECTED": return 2;
    case "APPROVED": case "COMPLETING": case "RECONCILING": return 3;
    case "COMPLETED": case "HANDED_OFF": return 4;
    default:
      if (intent.approval && (intent.approval.status === "APPROVED" || intent.approval.status === "CONSUMED")) {
        return 3;
      }
      return intent.quote ? 2 : 1;
  }
}

function milestones(intent) {
  const handoff = intent.merchant && intent.merchant.completion === "handoff";
  const at = reached(intent);
  const labels = ["Proposed", "Quoted by the store", at > 2 ? "Approved by you" : "Waiting for your approval",
      handoff ? "Handed off to the store" : "Sent to the store", "Order placed at the store"];
  const steps = labels.map((label, i) => ({label: label, mark: i < at ? "done" : i === at ? "current" : "pending"}));
  const ending = ENDINGS.has(intent.state) ? endingText(intent) : "";
  if (ending) {
    return steps.slice(0, at).concat([{label: ending, mark: "ended"}]);
  }
  if (intent.state === "COMPLETED") {
    steps[4].mark = "done";
  } else if (intent.state === "HANDED_OFF") {
    const text = followUpText(intent.store_order, intent.follow_up);
    if (intent.store_order) {
      steps[4].mark = "done";
    } else if (intent.follow_up && intent.follow_up.watching) {
      steps[4] = {label: text, mark: "current"};
    } else if (text) {
      steps[4] = {label: text, mark: "ended"};
    } else {
      steps[4].mark = "pending";
    }
  }
  return steps;
}

// The marks in words, for a screen reader: the marker's shape is not the only signal.
const MARKS = new Map([["done", "Done: "], ["current", "Now: "], ["pending", "Next: "], ["ended", "Ended: "]]);

function showSteps(steps) {
  const list = $("steps");
  clear(list);
  steps.forEach((step) => {
    const item = el("li", null, step.mark);
    item.appendChild(el("span", MARKS.get(step.mark), "sr-only"));
    item.appendChild(document.createTextNode(step.label));
    if (step.mark === "current") {
      item.setAttribute("aria-current", "step");
    }
    list.appendChild(item);
  });
}
// </milestones>

// The run's other intents, one line each, beside the one the timeline follows (S22): merchant and state.
async function showOtherIntents(ids) {
  const list = $("other-intents");
  if (!currentIntent || ids.length < 2) {
    clear(list);
    return;
  }
  const lines = [];
  for (const id of ids) {
    if (id === currentIntent.id) {
      continue;
    }
    const result = await api("GET", "/api/intents/" + id);
    if (result.ok) {
      lines.push("Intent " + id + ": " + result.data.merchant_id + ", " + result.data.state.replace(/_/g, " ").toLowerCase());
    }
  }
  clear(list);
  lines.forEach((text) => list.appendChild(el("li", text)));
}

const LABELS = {
  proposed: "Proposed",
  policy_decision: "Policy checked",
  approval_opened: "Approval opened",
  approval_decided: "Approval decided",
  approval_invalid: "Approval did not match",
  complete_resent: "Complete re-sent",
  reconcile_read: "Reconciling: GET",
  reconcile_wait: "Reconciling: waiting",
  fulfillment_selection_needed: "Shipping selection needed",
  fulfillment_unsupported: "Shipping choice not supported",
  escalated: "Escalated",
  cancel_sent: "Cancel sent",
  orphan_probe_sent: "Looking for a lost session"
};

function describe(event) {
  const p = event.payload || {};
  if (event.type === "transition") {
    if (p.event === "PRICE_CHANGED") {
      return "Price changed: re-quoting";
    }
    return "Now " + String(p.to || "").replace(/_/g, " ").toLowerCase();
  }
  if (event.type === "approval_decided") {
    // Only the owner can decide an approval (T3), so the decider is always the signed-in person.
    return p.decision === "approve" ? "Approved by you" : p.decision === "reject" ? "Rejected by you" : "Approval decided";
  }
  if (event.type === "reconcile_read") {
    return "Reconciling: GET " + (p.outcome || "");
  }
  return LABELS[event.type] || event.type;
}

async function loadEvents(id) {
  const result = await api("GET", "/api/intents/" + id + "/events");
  if (!result.ok) {
    return;
  }
  clear($("timeline"));
  $("events-toggle").textContent = "Show all " + result.data.length + " events";
  $("all-events").hidden = false;
  result.data.forEach((event) => {
    $("timeline").appendChild(el("li", describe(event)));
  });
}

// ---- the merchant mode ----------------------------------------------------------------------------------------------

// <merchant-mode>
// 24.3: the header badge and the Developer tools follow the merchant of the intent the timeline follows; before one,
// the lab's list. The store's domain is set as text only.
function liveDomain() {
  if (currentIntent && currentIntent.merchant) {
    return typeof currentIntent.merchant.shop_domain === "string" ? currentIntent.merchant.shop_domain : "";
  }
  const live = labMerchants.find((m) => m.live === true && typeof m.shop_domain === "string");
  return live ? live.shop_domain : "";
}

function showMerchantMode() {
  const badge = $("merchant-mode");
  const domain = liveDomain();
  badge.hidden = !((currentIntent && currentIntent.merchant) || labAvailable);
  badge.textContent = domain ? "Live store · " + domain : "Lab merchants";
  badge.className = domain ? "badge live" : "badge";
  $("dev-tools").hidden = !labAvailable || domain !== "";
  if (domain) {
    $("dev-tools").open = false;
  }
}
// </merchant-mode>

// ---- the fault lab and the order counter ---------------------------------------------------------------------------

async function startLab() {
  const list = await api("GET", "/api/lab/merchants");
  if (!list.ok) {
    return;     // not the local profile: the lab does not exist here
  }
  labMerchants = list.data.merchants;
  labAvailable = true;
  const select = $("lab-merchant");
  clear(select);
  clear($("order-counters"));
  list.data.merchants.forEach((m) => {
    select.appendChild(el("option", m.name + (m.status === "ACTIVE" ? "" : " (suspended)")))
        .setAttribute("value", m.merchant_id);
    const box = el("div");
    box.appendChild(el("dt", m.name));
    const count = el("dd", "0");
    box.appendChild(count);
    $("order-counters").appendChild(box);
    counters.set(m.merchant_id, count);
  });
  showMerchantMode();
  refreshOrders();
  window.setInterval(refreshOrders, 2000);
}

async function refreshOrders() {
  for (const [merchant, count] of counters) {
    const result = await api("GET", "/api/lab/orders?merchant_id=" + encodeURIComponent(merchant));
    if (result.ok && Array.isArray(result.data.orders)) {
      count.textContent = String(result.data.orders.length);
    }
  }
}

async function preset(name) {
  const result = await api("PUT", "/api/lab/faults", {preset: name, merchant_id: $("lab-merchant").value});
  setStatus(result.ok ? "Fault set: " + name.replace(/_/g, " ") + "." : errorText(result), !result.ok);
}

async function clearFaults() {
  const result = await api("DELETE", "/api/lab/faults?merchant_id=" + encodeURIComponent($("lab-merchant").value));
  setStatus(result.ok || result.status === 204 ? "Faults cleared." : errorText(result), !result.ok);
}

async function resetLab() {
  const result = await api("POST", "/api/lab/reset", {});
  setStatus(result.ok || result.status === 204 ? "Lab reset." : errorText(result), !result.ok);
  refreshOrders();
}

boot();
