"""H5 Stage 3 (docs/interop/shopify-live.md): one live run of the agent at the owner's own development store. Python 3
standard library only; run with `python3 -I` through scripts/shopify-live.sh.

The service runs in the local profile with a temporary merchants.yaml that lists the store alone (transport mcp,
completion handoff, owned, requires_buyer_ip) and the scripted model, so the run is: search, propose, approve as the
owner, hand off. Beside it the script records, read-only, the store's answers the H5 notes need: get_checkout of the
agent's checkout right after its quote and about 60 s later, and one probe checkout of the same lines (create, get,
get 60 s later, update, cancel) whose raw totals the service does not keep. It never completes (N1), never sends a card,
a payment object or an address. Every printed or recorded line goes through the spike's redact(); the handoff URL is
printed once, to the terminal, for the buyer only. Never run in CI."""
import json
import os
import secrets as random_bytes
import shutil
import signal
import sqlite3
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shopify_spike as spike  # noqa: E402  (the spike's redaction, sanitizing and call, reused as they are)

TOKEN_ENDPOINT = "https://api.shopify.com/auth/access_token"
AGENT_VERSION = "2026-04-08"     # 26.2: the version of Shopify's sample agent profile, the fixtures' path (C10)
USER = "alice"
MAX_APPROVALS = 4                # one more than the P8 cap allows (3), so a cap that does not hold is seen
QUOTE_TIMEOUT = 120
SETTLE_TIMEOUT = 600             # 7.2: the 5-minute handoff deadline, a worker pass and the GET retries, with room
TERMINAL = ("HANDED_OFF", "COMPLETED", "REJECTED", "FAILED_NOT_CHARGED", "ESCALATED")
PROBE_GAP = 60


def remember(value):
    """A value that must never be printed or recorded from now on (I8)."""
    if value and value not in spike.TOKEN:
        spike.TOKEN.append(value)


def say(line):
    spike.say(line)


def configuration(env, argv, dry):
    for flag, reason in (("--i-own-this-store", "to state that the store is a development store you own (DESIGN 26.5)"),
                         ("--with-buyer-ip", "the store refuses every call without the buyer IP (spike, VERIFY 2)")):
        if flag not in argv:
            raise SystemExit("refused: pass %s, %s" % (flag, reason))
    needed = ["SHOPIFY_CLIENT_ID", "SHOPIFY_CLIENT_SECRET", "SHOPIFY_STORE_DOMAIN", "AGENT_PROFILE_URL", "BUYER_IP"]
    missing = [v for v in needed if not env.get(v)]
    if missing and not dry:
        raise SystemExit("missing environment: " + ", ".join(missing))
    domain = (env.get("SHOPIFY_STORE_DOMAIN") or "<store>.myshopify.com").strip().lower()
    if not domain.endswith(".myshopify.com") or "/" in domain or ":" in domain:
        raise SystemExit("refused: SHOPIFY_STORE_DOMAIN must be a host ending in .myshopify.com")
    return domain


def option(argv, name, default):
    return argv[argv.index(name) + 1] if name in argv else default


def merchants_yaml(domain):
    return "\n".join([
        "merchants:",
        "  - id: dev-store",
        "    name: Development store",
        "    url: https://%s" % domain,
        "    transport: mcp",
        "    completion: handoff",
        "    owned: true",
        "    shop_domain: %s" % domain,
        "    credential_ref: env:SHOPIFY_CLIENT",
        "    requires_buyer_ip: true",
        "    ucp_versions: [\"%s\"]" % AGENT_VERSION,
        ""])


POLICY_YAML = "per_purchase_cap_minor: 200000\ndaily_cap_minor: 400000\n"


def service_env(env, tmp, port):
    """The service's whole environment: built from nothing, so no other variable of the owner's shell reaches it."""
    out = {k: env[k] for k in ("PATH", "HOME", "LANG", "TMPDIR", "JAVA_HOME") if env.get(k)}
    approval_secret = random_bytes.token_hex(32)
    remember(approval_secret)
    out.update({
        "AGENT_PROFILE": "local",
        "AGENT_MODEL": "scripted",
        "SERVER_PORT": str(port),
        "PUBLIC_ORIGIN": "http://localhost:%d" % port,
        "DATABASE_URL": "jdbc:sqlite:" + os.path.join(tmp, "ucplab.db"),
        "MERCHANTS_FILE": os.path.join(tmp, "merchants.yaml"),
        "POLICY_FILE": os.path.join(tmp, "policy.yaml"),
        "APPROVAL_SECRET": approval_secret,
        "SHOPIFY_CLIENT": "%s:%s" % (env.get("SHOPIFY_CLIENT_ID", ""), env.get("SHOPIFY_CLIENT_SECRET", "")),
        "BUYER_IP": env.get("BUYER_IP", ""),
        "UCP_AGENT_PROFILE": env.get("AGENT_PROFILE_URL", ""),
        "UCP_AGENT_VERSION": AGENT_VERSION,
    })
    return out


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Api:
    """The service's REST API as the CLI uses it (ApiSession): dev-login, then the session cookie and the CSRF token."""

    def __init__(self, base):
        self.base = base
        self.cookie = ""
        self.csrf = ""
        self.opener = urllib.request.build_opener(NoRedirect)

    def raw(self, method, path, body=None):
        headers = {"Origin": self.base, "Accept": "application/json"}
        if self.cookie:
            headers["Cookie"] = "ucplab_session=" + self.cookie
        if body is not None:
            headers["Content-Type"] = "application/json"
        if self.csrf and method != "GET":
            headers["X-CSRF-Token"] = self.csrf
        req = urllib.request.Request(self.base + path, method=method, headers=headers,
                                     data=json.dumps(body).encode() if body is not None else None)
        try:
            with self.opener.open(req, timeout=30) as r:
                return r.status, r.headers, r.read().decode()
        except urllib.error.HTTPError as e:
            return e.code, e.headers, e.read().decode()

    def sign_in(self, user):
        status, headers, _ = self.raw("POST", "/auth/dev-login", {"user": user})
        for value in headers.get_all("Set-Cookie") or []:
            if value.startswith("ucplab_session="):
                self.cookie = value[len("ucplab_session="):].split(";", 1)[0]
                remember(self.cookie)
        if status != 303 or not self.cookie:
            raise RuntimeError("sign-in refused (%s)" % status)
        status, headers, _ = self.raw("GET", "/api/me")
        self.csrf = headers.get("X-CSRF-Token") or ""
        remember(self.csrf)
        if status != 200 or not self.csrf:
            raise RuntimeError("sign-in could not be confirmed (%s)" % status)

    def json(self, method, path, body=None):
        status, _, text = self.raw(method, path, body)
        if status >= 300:
            raise RuntimeError("%s %s answered %s: %s" % (method, path, status, spike.redact(text[:300])))
        return json.loads(text) if text else {}


def wait_ready(base, proc, timeout=120):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if proc.poll() is not None:
            raise RuntimeError("the service stopped while starting (exit %s); see service.log" % proc.returncode)
        try:
            with urllib.request.urlopen(base + "/readyz", timeout=5) as r:
                if r.status == 200:
                    return
        except (urllib.error.URLError, OSError):
            pass
        time.sleep(1)
    raise RuntimeError("the service was not ready within %d s" % timeout)


def wait_state(api, intent, wanted, timeout):
    deadline = time.time() + timeout
    while True:
        body = api.json("GET", "/api/intents/" + intent)
        if body.get("state") in wanted or time.time() > deadline:
            return body
        time.sleep(1)


def db_row(db, sql, args):
    with sqlite3.connect("file:%s?mode=ro" % db, uri=True, timeout=10) as c:
        return c.execute(sql, args).fetchone()


def db_rows(db, sql, args):
    with sqlite3.connect("file:%s?mode=ro" % db, uri=True, timeout=10) as c:
        return c.execute(sql, args).fetchall()


class Store:
    """The script's own read-only and probe calls to the store, through spike.call: redacted, recorded, host-checked."""

    def __init__(self, domain, dry):
        self.domain = domain
        self.dry = dry
        self.meta = {"ucp-agent": {"profile": os.environ.get("AGENT_PROFILE_URL", "<agent profile>")}}
        self.endpoint = None
        self.headers = {}
        self.ids = 10

    def open(self):
        tok = spike.call("token", "POST", TOKEN_ENDPOINT, {},
                         {"client_id": os.environ.get("SHOPIFY_CLIENT_ID", "<redacted>"),
                          "client_secret": os.environ.get("SHOPIFY_CLIENT_SECRET", "<redacted>"),
                          "grant_type": "client_credentials"}, self.dry)
        if tok.get("access_token"):
            spike.TOKEN.insert(0, tok["access_token"])
        profile = spike.call("store profile", "GET", "https://%s/.well-known/ucp" % self.domain, {}, None, self.dry)
        self.endpoint = spike.profile_endpoint(profile) or ("https://%s/api/ucp/mcp" % self.domain if self.dry else None)
        if not self.endpoint:
            raise SystemExit("stopped: the store's profile names no MCP endpoint in ucp.services")
        host = urllib.parse.urlparse(self.endpoint).hostname
        if not self.dry and host != self.domain:
            raise SystemExit("refused: the profile's MCP endpoint host is not the stated store (DESIGN 26.5)")
        self.headers = {"Authorization": "Bearer " + (tok.get("access_token") or "<token>"),
                        "Shopify-Buyer-IP": os.environ.get("BUYER_IP", "<redacted>")}

    def tool(self, step, name, arguments, record_as, mutating=False, key=None):
        """One tools/call; a mutating one under `key`, or a fresh one. A create or update's request is recorded beside
        its answer (record_as + ".request"), redacted like the answer."""
        self.ids += 1
        meta = dict(self.meta, **({"idempotency-key": key or str(uuid.uuid4())} if mutating else {}))
        body = {"jsonrpc": "2.0", "id": self.ids, "method": "tools/call",
                "params": {"name": name, "arguments": dict(arguments, meta=meta)}}
        if record_as and not self.dry and name in ("create_checkout", "update_checkout"):
            spike.record(record_as + ".request", body)
        return spike.content(spike.call(step, "POST", self.endpoint, self.headers, body, self.dry, record_as))


def totals_line(name, checkout):
    """One redacted line of what the comparison needs: status, currency and every totals entry by type."""
    totals = {t.get("type"): t.get("amount") for t in checkout.get("totals") or [] if isinstance(t, dict)}
    messages = ["%s/%s" % (m.get("code"), m.get("severity")) for m in checkout.get("messages") or [] if isinstance(m, dict)]
    return "%s: status %s, currency %s, totals %s, messages %s" % (
        name, checkout.get("status"), checkout.get("currency"), json.dumps(totals, sort_keys=True), ", ".join(messages))


def stop_on_signals():
    """SIGTERM and SIGHUP (a kill, a closed terminal) end the script as Ctrl-C does, so every cleanup runs. Returns the
    handlers they had."""
    def stop(signum, frame):
        raise KeyboardInterrupt("signal %d" % signum)
    return {s: signal.signal(s, stop) for s in (signal.SIGTERM, signal.SIGHUP)}


def ignore_signals():
    """No signal cuts a cleanup short. Returns the handlers they had."""
    return {s: signal.signal(s, signal.SIG_IGN) for s in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP)}


def restore_signals(old):
    for s, handler in old.items():
        signal.signal(s, handler)


def run_probe(store, lines, probe, after_gap, sleep=time.sleep):
    """The probe checkout: create, get at once, get PROBE_GAP s later (after_gap runs between), update with the same
    lines, cancel. probe["id"] is set as soon as the store names the checkout and cleared once it is canceled;
    probe["sent"] once the create may have reached the store."""
    probe["sent"] = True
    created = store.tool("create_checkout (probe, the agent's lines)", "create_checkout",
                         {"checkout": {"line_items": lines}}, "probe_create_checkout", True)
    probe["id"] = created.get("id")
    say(totals_line("probe create", created))
    if probe["id"]:
        say(totals_line("probe get 0 s", store.tool("get_checkout (probe, right after create)", "get_checkout",
                                                    {"id": probe["id"]}, "probe_get_checkout_0s")))
        sleep(PROBE_GAP)
        say(totals_line("probe get 60 s", store.tool("get_checkout (probe, 60 s later)", "get_checkout",
                                                     {"id": probe["id"]}, "probe_get_checkout_60s")))
    after_gap()
    if probe["id"]:
        got = store.tool("get_checkout (probe, its line ids)", "get_checkout", {"id": probe["id"]}, None)
        update_lines = [dict({"id": li["id"]} if li.get("id") else {}, item={"id": (li.get("item") or {}).get("id")},
                             quantity=li.get("quantity")) for li in got.get("line_items") or []]
        say(totals_line("probe update", store.tool("update_checkout (probe, same lines)", "update_checkout",
                                                   {"id": probe["id"], "checkout": {"line_items": update_lines}},
                                                   "probe_update_checkout", True)))
        canceled = store.tool("cancel_checkout (probe)", "cancel_checkout", {"id": probe["id"]}, "probe_cancel_checkout", True)
        say("probe canceled (status: %s)" % canceled.get("status"))
        probe["id"] = None


def finish_probe(store, probe):
    """On the way out: a probe checkout still open is canceled, with no signal able to cut the cancel short; one that
    cannot be (it failed, or the store never named the checkout) is said out loud."""
    if probe["id"]:
        old = ignore_signals()
        try:
            store.tool("cancel_checkout (probe, on the way out)", "cancel_checkout", {"id": probe["id"]},
                       "probe_cancel_checkout", True)
            probe["id"] = None
        except BaseException as e:      # a checkout left open must be loud
            say("WARNING: the probe checkout may be left open: %s" % (str(e) or type(e).__name__))
        finally:
            restore_signals(old)
    elif probe.get("sent"):
        say("WARNING: a probe checkout may be open at the store, which named no id for it (the create was cut short or "
            "answered without one): cancel it in the store admin")


def probe_session(store, lines, after_gap, sleep=time.sleep):
    """The probe from create to cancel; a probe still open on the way out, by an error, a timeout, Ctrl-C, SIGTERM or
    SIGHUP, is canceled."""
    probe = {"id": None, "sent": False}
    old = stop_on_signals()
    try:
        run_probe(store, lines, probe, after_gap, sleep)
    finally:
        finish_probe(store, probe)
        restore_signals(old)


ATTEMPT_COLUMNS = ("seq", "op", "revision", "idempotency_key", "status", "http_status", "request_body", "response_json",
                   "sent_at", "responded_at")


def save_intent_evidence(api, db, intent, evidence):
    """The intent as the owner's API shows it, its events and its merchant attempts (each with its request body and the
    outcome the service stored), into evidence/, sanitized and redacted. Kept on every way out, a failed quote
    included; each part on its own, so one that cannot be read does not lose the rest."""
    def write(name, value):
        with open(os.path.join(evidence, name), "w") as f:
            f.write(spike.redact(json.dumps(spike.sanitize(value), indent=2, sort_keys=True)) + "\n")
    for name, path in (("intent.json", "/api/intents/%s" % intent), ("events.json", "/api/intents/%s/events" % intent)):
        try:
            body = api.json("GET", path)
            if isinstance(body, dict) and body.get("handoff_url"):
                remember(body["handoff_url"])
            write(name, body)
        except Exception as e:
            say("WARNING: %s not saved: %s" % (name, e))
    try:
        rows = db_rows(db, "SELECT %s FROM merchant_attempt WHERE intent_id = ? ORDER BY seq" % ", ".join(ATTEMPT_COLUMNS),
                       (intent,))
        write("attempts.json", [dict(zip(ATTEMPT_COLUMNS, r)) for r in rows])
        for r in rows:
            a = dict(zip(ATTEMPT_COLUMNS, r))
            say("attempt %s %s: %s, http %s, %s" % (a["seq"], a["op"], a["status"], a["http_status"],
                                                   spike.redact(json.dumps(spike.sanitize(a["response_json"])))[:300]))
    except Exception as e:
        say("WARNING: attempts.json not saved: %s" % e)


def replay_failed_quote(store, db, intent):
    """The last create or update of a quote that opened no approval, sent again with its own idempotency key and body,
    so the store's raw answer (which the service keeps only as a summary) is recorded. A store that honours the key
    answers as it did the first time and creates nothing new; a checkout the answer names is canceled either way (N1)."""
    rows = db_rows(db, "SELECT op, idempotency_key, request_body FROM merchant_attempt WHERE intent_id = ? "
                       "AND op IN ('CREATE', 'UPDATE') ORDER BY seq DESC LIMIT 1", (intent,))
    if not rows:
        say("no create or update to replay: the quote failed before any call reached the gateway")
        return
    op, key, body = rows[0]
    checkout = json.loads(body) if body else {}
    if not store.endpoint:
        store.open()
    if op == "CREATE":
        tool, name, arguments = "create_checkout", "agent_create_replay", {"checkout": checkout}
    else:
        session = (db_row(db, "SELECT session_id FROM purchase_intent WHERE id = ?", (intent,)) or [None])[0]
        tool, name, arguments = "update_checkout", "agent_update_replay", {"id": session, "checkout": checkout}
    try:
        answer = store.tool("%s (replay of the agent's, same key)" % tool, tool, arguments, name, True, key=key)
    except Exception as e:      # the store's error answer is recorded as name.error by spike.call
        say("replay answered with an error: %s" % e)
        return
    say(totals_line(name, answer))
    if answer.get("id"):
        try:
            canceled = store.tool("cancel_checkout (the replay's checkout)", "cancel_checkout", {"id": answer["id"]},
                                  "agent_replay_cancel", True)
            say("replay's checkout canceled (status: %s)" % canceled.get("status"))
        except Exception as e:      # a checkout left open must be loud
            say("WARNING: the replay's checkout may be left open: %s" % e)


DRAIN_TIMEOUT = 30         # the recovery worker runs every 5 s (7.6); three cancel attempts fit with room
GAVE_UP = ("cancel_gave_up", "orphan_probe_gave_up")


def checkout_named(session):
    """A checkout as the owner can find it in the store admin: its id with the access key masked (I8)."""
    return spike.sanitize(session) if session else "no checkout id (a create that may have made one the store never " \
                                                   "named: look for an open checkout made at the run's time)"


def owed_cancels(db, intents):
    marks = ", ".join("?" * len(intents))
    return db_rows(db, "SELECT id, session_id FROM purchase_intent WHERE cancel_pending = 1 AND id IN (%s) ORDER BY id"
                   % marks, tuple(intents))


def drain_cancels(db, intents, timeout=DRAIN_TIMEOUT, sleep=time.sleep, clock=time.time):
    """Before the service stops: waits until none of the run's intents still owes a cancel (7.6), up to `timeout`, so the
    recovery worker can send them. Names, key masked, every checkout still owed at the bound and every one the worker
    gave up on, so the owner can cancel it in the store admin. Returns the (intent, session) pairs still owed."""
    if not intents:
        return []
    deadline = clock() + timeout
    owed = owed_cancels(db, intents)
    while owed and clock() < deadline:
        sleep(1)
        owed = owed_cancels(db, intents)
    for intent, session in owed:
        say("WARNING: intent %s still owes a cancel after %d s, of %s: cancel it in the store admin"
            % (intent, timeout, checkout_named(session)))
    marks = ", ".join("?" * len(intents))
    for intent, session, kind in db_rows(
            db, "SELECT e.intent_id, p.session_id, e.type FROM intent_event e JOIN purchase_intent p ON p.id = e.intent_id "
                "WHERE e.type IN (?, ?) AND e.intent_id IN (%s) ORDER BY e.intent_id, e.seq" % marks, GAVE_UP + tuple(intents)):
        say("WARNING: the service gave up (%s) on intent %s, of %s: cancel it in the store admin"
            % (kind, intent, checkout_named(session)))
    return owed


def approve_all(api, intent, db, interactive, auto):
    """Approves each card the run opens, up to MAX_APPROVALS, and returns the settled intent and the count."""
    approvals = 0
    body = wait_state(api, intent, ("AWAITING_APPROVAL",) + TERMINAL, QUOTE_TIMEOUT)
    while body.get("state") == "AWAITING_APPROVAL" and approvals < MAX_APPROVALS:
        card = api.json("GET", "/api/intents/%s/approval" % intent)
        remember(card.get("token"))
        snap = card.get("snapshot") or {}
        say("approval card %d: lines %s, total %s %s, completion %s" % (
            approvals + 1, json.dumps(snap.get("lines")), snap.get("total_minor"), snap.get("currency"),
            card.get("completion", "agent")))
        if interactive:
            if input("Approve this card? [y/N] ").strip().lower() != "y":
                api.json("POST", "/api/intents/%s/approval" % intent, {"token": card["token"], "decision": "reject"})
                say("rejected by the owner")
                return wait_state(api, intent, TERMINAL, 30), approvals
        elif not auto:
            raise SystemExit("stopped: no terminal to ask on; pass --approve to approve each card the run opens")
        api.json("POST", "/api/intents/%s/approval" % intent, {"token": card["token"], "decision": "approve"})
        approvals += 1
        say("approved card %d" % approvals)
        body = wait_state(api, intent, ("AWAITING_APPROVAL",) + TERMINAL, SETTLE_TIMEOUT)
    return body, approvals


def main(argv):
    dry = "--dry-run" in argv
    domain = configuration(os.environ, argv, dry)
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    port = int(option(argv, "--port", "8080"))
    query = option(argv, "--query", "snowboard")
    record = os.path.abspath(option(argv, "--record", os.path.join(root, "notes/h5/stage3/recordings")))
    evidence = os.path.abspath(option(argv, "--evidence", os.path.join(root, "notes/h5/stage3/evidence")))
    jar = os.path.join(root, "agent-service/target/agent-service-0.1.0-SNAPSHOT.jar")
    base = "http://localhost:%d" % port
    interactive = sys.stdin.isatty() and "--approve" not in argv
    store = Store(domain, dry)

    if dry:
        say("DRY-RUN merchants.yaml (temporary, deleted on exit):\n" + merchants_yaml(domain))
        say("DRY-RUN policy.yaml (temporary): " + POLICY_YAML.strip().replace("\n", ", "))
        shown = {k: ("<redacted>" if k in ("SHOPIFY_CLIENT", "BUYER_IP", "APPROVAL_SECRET") else v)
                 for k, v in service_env(os.environ, "<tmp>", port).items() if k not in ("PATH", "HOME", "LANG", "TMPDIR")}
        say("DRY-RUN service: java -jar %s, environment %s, log to %s/service.log" % (jar, json.dumps(shown, sort_keys=True), evidence))
        say("DRY-RUN wait for %s/readyz; sign in as %s; POST /api/runs {\"message\": %s}" % (base, USER, json.dumps(query)))
        say("DRY-RUN wait for AWAITING_APPROVAL; read the agent's checkout id from the temporary database (memory only)")
        say("DRY-RUN if the quote opens no approval: replay the agent's last create or update with its own key and body, "
            "record request and answer, cancel any checkout the answer names, then stop")
        store.open()
        store.tool("get_checkout (the agent's checkout, right after its quote)", "get_checkout", {"id": "<agent checkout>"},
                   "agent_get_checkout_0s")
        store.tool("create_checkout (probe, the agent's lines)", "create_checkout",
                   {"checkout": {"line_items": [{"item": {"id": "<item>"}, "quantity": 1}]}}, "probe_create_checkout", True)
        store.tool("get_checkout (probe, right after create)", "get_checkout", {"id": "<probe checkout>"}, "probe_get_checkout_0s")
        say("DRY-RUN wait %d s" % PROBE_GAP)
        store.tool("get_checkout (probe, 60 s later)", "get_checkout", {"id": "<probe checkout>"}, "probe_get_checkout_60s")
        store.tool("get_checkout (the agent's checkout, 60 s later)", "get_checkout", {"id": "<agent checkout>"},
                   "agent_get_checkout_60s")
        store.tool("update_checkout (probe, same lines)", "update_checkout",
                   {"id": "<probe checkout>", "checkout": {"line_items": [{"id": "<line>", "item": {"id": "<item>"},
                                                                           "quantity": 1}]}},
                   "probe_update_checkout", True)
        store.tool("cancel_checkout (probe)", "cancel_checkout", {"id": "<probe checkout>"}, "probe_cancel_checkout", True)
        say("DRY-RUN show each approval card; approve it (asked on the terminal, or --approve), up to %d" % MAX_APPROVALS)
        say("DRY-RUN wait for HANDED_OFF or another end, up to %d s; print the handoff URL once, for the buyer only" % SETTLE_TIMEOUT)
        say("DRY-RUN record the agent's attempts (op, status, the stored summary) and the intent's events")
        if "--cancel-after" in argv:
            say("DRY-RUN --cancel-after: after the owner has seen the store's page, cancel the agent's checkout")
            store.tool("cancel_checkout (the agent's checkout, VERIFY 5)", "cancel_checkout", {"id": "<agent checkout>"},
                       "agent_cancel_checkout", True)
        say("DRY-RUN on every way out: save intent.json, events.json and attempts.json (redacted) to %s" % evidence)
        say("DRY-RUN wait up to %d s for the run's owed cancels to drain; name, key masked, any still owed or given up"
            % DRAIN_TIMEOUT)
        say("DRY-RUN stop the service; delete the temporary directory")
        return 0

    if not os.path.isfile(jar):
        raise SystemExit("build first: ./mvnw -B -ntp -DskipTests package")
    os.makedirs(record, exist_ok=True)
    os.makedirs(evidence, exist_ok=True)
    spike.RECORD[0] = record
    remember(os.environ["BUYER_IP"])
    stop_on_signals()
    tmp = tempfile.mkdtemp(prefix="ucplab-live-")
    proc = None
    log = None
    api = None
    intent = None
    intents = []
    db = os.path.join(tmp, "ucplab.db")
    try:
        with open(os.path.join(tmp, "merchants.yaml"), "w") as f:
            f.write(merchants_yaml(domain))
        with open(os.path.join(tmp, "policy.yaml"), "w") as f:
            f.write(POLICY_YAML)
        log = open(os.path.join(evidence, "service.log"), "w")
        proc = subprocess.Popen(["java", "-jar", jar], env=service_env(os.environ, tmp, port), stdout=log,
                                stderr=subprocess.STDOUT, cwd=tmp)
        wait_ready(base, proc)
        say("service ready")
        api = Api(base)
        api.sign_in(USER)
        run = api.json("POST", "/api/runs", {"message": query})
        deadline = time.time() + QUOTE_TIMEOUT
        while run.get("status") == "running" and time.time() < deadline:
            time.sleep(1)
            run = api.json("GET", "/api/runs/" + run["run_id"])
        with open(os.path.join(evidence, "run.json"), "w") as f:
            f.write(spike.redact(json.dumps(run, indent=2, sort_keys=True)) + "\n")
        intents[:] = run.get("intent_ids") or []
        say("run %s %s, intents %s" % (run.get("run_id"), run.get("status"), intents))
        if not intents:
            raise SystemExit("stopped: the run proposed no purchase (see run.json)")
        intent = intents[0]
        body = wait_state(api, intent, ("AWAITING_APPROVAL",) + TERMINAL, QUOTE_TIMEOUT)
        say("intent %s %s" % (intent, body.get("state")))
        if body.get("state") != "AWAITING_APPROVAL":
            replay_failed_quote(store, db, intent)
            raise SystemExit("stopped: the quote did not open an approval (%s, %s)" % (body.get("state"),
                                                                                     body.get("failure_reason")))
        session = (db_row(db, "SELECT session_id FROM purchase_intent WHERE id = ?", (intent,)) or [None])[0]
        card = api.json("GET", "/api/intents/%s/approval" % intent)
        remember(card.get("token"))
        lines = [{"item": {"id": l["item_id"]}, "quantity": l["quantity"]} for l in (card.get("snapshot") or {}).get("lines") or []]

        store.open()
        say(totals_line("agent get 0 s", store.tool("get_checkout (the agent's checkout, right after its quote)",
                                                     "get_checkout", {"id": session}, "agent_get_checkout_0s")))
        probe_session(store, lines, lambda: say(totals_line("agent get 60 s", store.tool(
            "get_checkout (the agent's checkout, 60 s later)", "get_checkout", {"id": session}, "agent_get_checkout_60s"))))

        body, approvals = approve_all(api, intent, db, interactive, "--approve" in argv)
        state = body.get("state")
        requotes, = db_row(db, "SELECT requote_count FROM purchase_intent WHERE id = ?", (intent,))
        stored = db_rows(db, "SELECT status FROM approval WHERE intent_id = ? ORDER BY seq", (intent,))
        say("intent %s ended %s%s; approvals by the owner %d; approvals stored %d (%s); requote_count %d" % (
            intent, state, (", " + body["failure_reason"]) if body.get("failure_reason") else "", approvals,
            len(stored), ", ".join(s for s, in stored), requotes))
        attempts = [{"seq": s, "op": op, "status": st, "response": json.loads(r) if r else None}
                    for s, op, st, r in db_rows(db, "SELECT seq, op, status, response_json FROM merchant_attempt "
                                                    "WHERE intent_id = ? ORDER BY seq", (intent,))]
        spike.record("agent_attempts", {"intent_state": state, "failure_reason": body.get("failure_reason"),
                                        "approvals": approvals, "requote_count": requotes, "attempts": attempts})
        url = body.get("handoff_url")
        if state == "HANDED_OFF" and url:
            remember(url)
            print("handoff URL (for the buyer only; not logged): " + url, flush=True)
            if "--cancel-after" in argv:
                if sys.stdin.isatty():
                    input("Open the URL, look at the store's page, then press Enter to cancel the checkout. ")
                canceled = store.tool("cancel_checkout (the agent's checkout, VERIFY 5)", "cancel_checkout",
                                      {"id": session}, "agent_cancel_checkout", True)
                say("agent checkout canceled (status: %s)" % canceled.get("status"))
        return 0
    finally:
        ignore_signals()
        if intent and api:
            save_intent_evidence(api, db, intent, evidence)
        if proc and proc.poll() is None and intents:
            try:
                drain_cancels(db, intents)
            except Exception as e:      # the service must still be stopped
                say("WARNING: the owed cancels could not be read: %s" % e)
        if proc and proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                proc.kill()
        if log:
            log.close()
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
