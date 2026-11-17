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

    def tool(self, step, name, arguments, record_as, mutating=False):
        self.ids += 1
        meta = dict(self.meta, **({"idempotency-key": str(uuid.uuid4())} if mutating else {}))
        body = {"jsonrpc": "2.0", "id": self.ids, "method": "tools/call",
                "params": {"name": name, "arguments": dict(arguments, meta=meta)}}
        return spike.content(spike.call(step, "POST", self.endpoint, self.headers, body, self.dry, record_as))


def totals_line(name, checkout):
    """One redacted line of what the comparison needs: status, currency and every totals entry by type."""
    totals = {t.get("type"): t.get("amount") for t in checkout.get("totals") or [] if isinstance(t, dict)}
    return "%s: status %s, currency %s, totals %s" % (name, checkout.get("status"), checkout.get("currency"),
                                                      json.dumps(totals, sort_keys=True))


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
        say("DRY-RUN stop the service; delete the temporary directory")
        return 0

    if not os.path.isfile(jar):
        raise SystemExit("build first: ./mvnw -B -ntp -DskipTests package")
    os.makedirs(record, exist_ok=True)
    os.makedirs(evidence, exist_ok=True)
    spike.RECORD[0] = record
    remember(os.environ["BUYER_IP"])
    tmp = tempfile.mkdtemp(prefix="ucplab-live-")
    proc = None
    probe_id = None
    log = None
    try:
        with open(os.path.join(tmp, "merchants.yaml"), "w") as f:
            f.write(merchants_yaml(domain))
        with open(os.path.join(tmp, "policy.yaml"), "w") as f:
            f.write(POLICY_YAML)
        db = os.path.join(tmp, "ucplab.db")
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
        intents = run.get("intent_ids") or []
        say("run %s %s, intents %s" % (run.get("run_id"), run.get("status"), intents))
        if not intents:
            raise SystemExit("stopped: the run proposed no purchase (see run.json)")
        intent = intents[0]
        body = wait_state(api, intent, ("AWAITING_APPROVAL",) + TERMINAL, QUOTE_TIMEOUT)
        say("intent %s %s" % (intent, body.get("state")))
        if body.get("state") != "AWAITING_APPROVAL":
            raise SystemExit("stopped: the quote did not open an approval (%s, %s)" % (body.get("state"),
                                                                                     body.get("failure_reason")))
        session = (db_row(db, "SELECT session_id FROM purchase_intent WHERE id = ?", (intent,)) or [None])[0]
        card = api.json("GET", "/api/intents/%s/approval" % intent)
        remember(card.get("token"))
        lines = [{"item": {"id": l["item_id"]}, "quantity": l["quantity"]} for l in (card.get("snapshot") or {}).get("lines") or []]

        store.open()
        say(totals_line("agent get 0 s", store.tool("get_checkout (the agent's checkout, right after its quote)",
                                                     "get_checkout", {"id": session}, "agent_get_checkout_0s")))
        created = store.tool("create_checkout (probe, the agent's lines)", "create_checkout",
                             {"checkout": {"line_items": lines}}, "probe_create_checkout", True)
        probe_id = created.get("id")
        say(totals_line("probe create", created))
        if probe_id:
            say(totals_line("probe get 0 s", store.tool("get_checkout (probe, right after create)", "get_checkout",
                                                        {"id": probe_id}, "probe_get_checkout_0s")))
            time.sleep(PROBE_GAP)
            say(totals_line("probe get 60 s", store.tool("get_checkout (probe, 60 s later)", "get_checkout",
                                                         {"id": probe_id}, "probe_get_checkout_60s")))
        say(totals_line("agent get 60 s", store.tool("get_checkout (the agent's checkout, 60 s later)", "get_checkout",
                                                     {"id": session}, "agent_get_checkout_60s")))
        if probe_id:
            got = store.tool("get_checkout (probe, its line ids)", "get_checkout", {"id": probe_id}, None)
            update_lines = [dict({"id": li["id"]} if li.get("id") else {}, item={"id": (li.get("item") or {}).get("id")},
                                 quantity=li.get("quantity")) for li in got.get("line_items") or []]
            say(totals_line("probe update", store.tool("update_checkout (probe, same lines)", "update_checkout",
                                                       {"id": probe_id, "checkout": {"line_items": update_lines}},
                                                       "probe_update_checkout", True)))
            canceled = store.tool("cancel_checkout (probe)", "cancel_checkout", {"id": probe_id}, "probe_cancel_checkout", True)
            say("probe canceled (status: %s)" % canceled.get("status"))
            probe_id = None

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
        events = api.json("GET", "/api/intents/%s/events" % intent)
        with open(os.path.join(evidence, "events.json"), "w") as f:
            f.write(spike.redact(json.dumps(events, indent=2, sort_keys=True)) + "\n")

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
        if probe_id:
            try:
                store.tool("cancel_checkout (probe, on the way out)", "cancel_checkout", {"id": probe_id},
                           "probe_cancel_checkout", True)
            except Exception as e:      # a checkout left open must be loud
                say("WARNING: the probe checkout may be left open: %s" % e)
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
