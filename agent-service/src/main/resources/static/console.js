// The UCP Checkout Lab console (24.1 to 24.3). Plain JavaScript, no framework, no build step. Every piece of data goes
// into the page with textContent through el(); there is no way in this file to write markup from data (24.3). The
// session is the cookie; the CSRF token and the approval token live in variables of this page only.
"use strict";

const TERMINAL = ["COMPLETED", "REJECTED", "FAILED_NOT_CHARGED", "ESCALATED"];
const RUN_WAITING = ["waiting_for_user", "done", "waiting_for_approval"];
const MERCHANT = "merchant-a";

let csrf = "";
let currentRun = null;      // {id, status}
let currentIntent = null;   // {id, state}
let intentTimer = null;
let runTimer = null;
let countdownTimer = null;

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

// ---- boot ----------------------------------------------------------------------------------------------------

async function boot() {
  const me = await api("GET", "/api/me");
  if (!me.ok) {
    return;
  }
  csrf = me.headers.get("X-CSRF-Token") || "";
  $("who").textContent = me.data.email;
  $("sign-out").hidden = false;
  $("sign-out").addEventListener("click", signOut);
  $("send").addEventListener("submit", send);
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

async function send(event) {
  event.preventDefault();
  const input = $("message");
  const text = input.value.trim();
  if (!text) {
    return;
  }
  input.value = "";
  const continuing = currentRun && RUN_WAITING.includes(currentRun.status);
  const result = continuing
      ? await api("POST", "/api/runs/" + currentRun.id + "/messages", {message: text})
      : await api("POST", "/api/runs", {message: text});
  if (!result.ok) {
    setStatus(errorText(result), true);
    return;
  }
  showRun(result.data);
}

function showRun(run) {
  currentRun = {id: run.run_id, status: run.status};
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
    if (run.status === "waiting_for_approval" && intents.length > 0) {
      loadApproval(intents[intents.length - 1]);
    }
  }
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
  (result.data.tool_events || []).forEach((e) => {
    $("tools").appendChild(el("li", e.tool + ": " + e.summary + " (" + e.took_ms + " ms)"));
  });
}

// ---- the approval card ---------------------------------------------------------------------------------------------
// The card is a separate component fed only by the approval endpoint (9.3 L4, I2): never by the chat, never by the run's
// messages. It reads the snapshot's lines, currency and total, the expiry and the token, and nothing else.

// <approval-card>
async function loadApproval(id) {
  const approvalResponse = await api("GET", "/api/intents/" + id + "/approval");
  if (!approvalResponse.ok) {
    hideApprovalCard();
    return;
  }
  showApprovalCard(id, approvalResponse);
}

function hideApprovalCard() {
  window.clearInterval(countdownTimer);
  $("approval-card").hidden = true;
}

function showApprovalCard(id, approvalResponse) {
  const approval = approvalResponse.data;
  const snapshot = approval.snapshot;
  const token = approval.token;       // kept in this closure only; never rendered, never stored
  const body = $("card-body");
  const foot = $("card-foot");
  clear(body);
  clear(foot);
  let linesTotal = 0;
  snapshot.lines.forEach((line) => {
    const row = el("tr");
    row.appendChild(el("td", line.item_id));
    row.appendChild(el("td", line.quantity, "num"));
    row.appendChild(el("td", money(line.unit_price_minor, snapshot.currency), "num"));
    row.appendChild(el("td", money(line.quantity * line.unit_price_minor, snapshot.currency), "num"));
    body.appendChild(row);
    linesTotal += line.quantity * line.unit_price_minor;
  });
  const charges = el("tr");
  charges.appendChild(el("td", "Other charges (tax)"));
  charges.appendChild(el("td", ""));
  charges.appendChild(el("td", ""));
  charges.appendChild(el("td", money(snapshot.total_minor - linesTotal, snapshot.currency), "num"));
  foot.appendChild(charges);
  const total = el("tr");
  total.appendChild(el("th", "Total"));
  total.appendChild(el("td", ""));
  total.appendChild(el("td", ""));
  total.appendChild(el("td", money(snapshot.total_minor, snapshot.currency), "num"));
  foot.appendChild(total);
  $("card-note").textContent = "Price and stock are not held.";
  $("approval-card").hidden = false;

  const expiresAt = Date.parse(approval.expires_at);
  window.clearInterval(countdownTimer);
  const tick = () => {
    const left = Math.max(0, Math.round((expiresAt - Date.now()) / 1000));
    $("card-expiry").textContent = left > 0
        ? "Expires in " + Math.floor(left / 60) + ":" + String(left % 60).padStart(2, "0")
        : "Expired. Ask for a new quote.";
    $("approve").disabled = left === 0;
  };
  tick();
  countdownTimer = window.setInterval(tick, 1000);

  $("approve").onclick = () => decide(id, token, "approve");
  $("reject").onclick = () => decide(id, token, "reject");
}
// </approval-card>

async function decide(id, token, decision) {
  $("approve").disabled = true;
  $("reject").disabled = true;
  const result = await api("POST", "/api/intents/" + id + "/approval", {token: token, decision: decision});
  $("reject").disabled = false;
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
  hideApprovalCard();
  setStatus(decision === "approve" ? "Approved. Completing the purchase." : "Rejected.");
  watchIntent(id);
}

// ---- the timeline ------------------------------------------------------------------------------------------------------

function watchIntent(id) {
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
    $("timeline-state").textContent = "Intent not found.";
    setStatus("Intent not found.", true);
    currentIntent = null;
    return;
  }
  if (!result.ok) {
    return;
  }
  const state = result.data.state;
  if (state !== currentIntent.state) {
    currentIntent.state = state;
    $("timeline-state").textContent = "Intent " + id + ": " + state.replace(/_/g, " ").toLowerCase();
    setStatus("Intent " + state.replace(/_/g, " ").toLowerCase() + ".");
    await loadEvents(id);
    if (state === "AWAITING_APPROVAL") {
      loadApproval(id);
    }
  }
  if (!TERMINAL.includes(state)) {
    intentTimer = window.setTimeout(pollIntent, 1000);
  }
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
  result.data.forEach((event) => {
    $("timeline").appendChild(el("li", describe(event)));
  });
}

// ---- the fault lab and the order counter ---------------------------------------------------------------------------

async function startLab() {
  const orders = await api("GET", "/api/lab/orders?merchant_id=" + MERCHANT);
  if (orders.status === 404) {
    return;     // not the local profile: the lab does not exist here
  }
  $("fault-lab").hidden = false;
  $("counters").hidden = false;
  showOrders(orders);
  window.setInterval(refreshOrders, 2000);
}

function showOrders(result) {
  if (result.ok && Array.isArray(result.data.orders)) {
    $("orders").textContent = String(result.data.orders.length);
  }
}

async function refreshOrders() {
  showOrders(await api("GET", "/api/lab/orders?merchant_id=" + MERCHANT));
}

async function preset(name) {
  const result = await api("PUT", "/api/lab/faults", {preset: name});
  setStatus(result.ok ? "Fault set: " + name.replace(/_/g, " ") + "." : errorText(result), !result.ok);
}

async function clearFaults() {
  const result = await api("DELETE", "/api/lab/faults");
  setStatus(result.ok || result.status === 204 ? "Faults cleared." : errorText(result), !result.ok);
}

async function resetLab() {
  const result = await api("POST", "/api/lab/reset", {});
  setStatus(result.ok || result.status === 204 ? "Lab reset." : errorText(result), !result.ok);
  refreshOrders();
}

boot();
