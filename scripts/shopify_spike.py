"""The Shopify spike of H1 C11 (docs/interop/shopify-spike.md): one token, one read-only catalog search, one store profile, ONE
checkout at the owner's own development store, then cancel. Python 3 standard library only; run with `python3 -I`.
It never completes, never sends a card, a payment object or an address. Secrets come from the environment only and are never
printed: every printed line goes through redact(). Never run in CI or by an agent."""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

SECRET_VARS = ("SHOPIFY_CLIENT_ID", "SHOPIFY_CLIENT_SECRET", "BUYER_IP")
TOKEN = []          # the access token, once there is one (memory only)
TEST_BASE = os.environ.get("SHOPIFY_SPIKE_TEST_BASE")   # tests only: every call goes to this local URL


def secrets():
    return [os.environ[v] for v in SECRET_VARS if os.environ.get(v)] + TOKEN


def redact(text):
    for s in secrets():
        text = text.replace(s, "<redacted>")
    return text


def say(line):
    print(redact(line), flush=True)


def url_for(url):
    if TEST_BASE:
        p = urllib.parse.urlparse(url)
        return TEST_BASE.rstrip("/") + p.path
    return url


def call(step, method, url, headers, body, dry):
    shown = {k: ("<redacted>" if k.lower() in ("authorization", "shopify-buyer-ip") else v) for k, v in headers.items()}
    if dry:
        say("DRY-RUN %s: %s %s headers=%s body=%s" % (step, method, url, json.dumps(shown), json.dumps(body)))
        return {}
    req = urllib.request.Request(url_for(url), data=json.dumps(body).encode() if body is not None else None, method=method,
                                 headers=dict(headers, **{"Content-Type": "application/json"}))
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            status, text = r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        status, text = e.code, e.read().decode()
    say("%s: HTTP %s" % (step, status))
    if status >= 300:
        raise RuntimeError("%s answered HTTP %s" % (step, status))
    return json.loads(text) if text else {}


def configuration(env):
    missing = [v for v in ("SHOPIFY_CLIENT_ID", "SHOPIFY_CLIENT_SECRET", "SHOPIFY_STORE_DOMAIN", "AGENT_PROFILE_URL", "BUYER_IP") if not env.get(v)]
    if missing:
        raise SystemExit("missing environment: " + ", ".join(missing))
    return env["SHOPIFY_STORE_DOMAIN"].strip().lower()


def main(argv):
    dry = "--dry-run" in argv
    domain = configuration(os.environ)
    if "--i-own-this-store" not in argv:
        raise SystemExit("refused: pass --i-own-this-store to state that %s is a development store you own (DESIGN 26.5)" % domain)
    profile = os.environ["AGENT_PROFILE_URL"]
    ip = os.environ["BUYER_IP"]
    meta = {"ucp-agent": {"profile": profile}}
    checkout_id = None
    endpoint = None
    try:
        tok = call("token", "POST", "https://api.shopify.com/auth/access_token", {},
                   {"client_id": os.environ["SHOPIFY_CLIENT_ID"], "client_secret": os.environ["SHOPIFY_CLIENT_SECRET"],
                    "grant_type": "client_credentials"}, dry)
        TOKEN.append(tok.get("access_token", "")) if tok.get("access_token") else None
        auth = {"Authorization": "Bearer " + (TOKEN[0] if TOKEN else "<token>")}
        search = call("catalog search (read-only)", "POST", "https://catalog.shopify.com/api/ucp/mcp", auth,
                      {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "search_catalog",
                       "arguments": {"meta": meta, "catalog": {"query": "mug"}}}}, dry)
        profile_doc = call("store profile", "GET", "https://%s/.well-known/ucp" % domain, {}, None, dry)
        endpoint = profile_doc.get("mcp_endpoint") or "https://%s/api/ucp/mcp" % domain
        host = urllib.parse.urlparse(endpoint).hostname
        if not dry and host != domain:
            raise SystemExit("refused: the profile's MCP endpoint host %s is not the stated store %s (DESIGN 26.5)" % (host, domain))
        item = os.environ.get("SPIKE_ITEM_ID", "<item id from the catalog search>")
        created = call("create_checkout", "POST", endpoint, dict(auth, **{"Shopify-Buyer-IP": ip}),
                       {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "create_checkout", "arguments": {
                           "meta": meta, "checkout": {"currency": "USD", "line_items": [{"item": {"id": item}, "quantity": 1}]}}}}, dry)
        checkout_id = created.get("id") or created.get("result", {}).get("id")
        cont = created.get("continue_url") or created.get("result", {}).get("continue_url")
        if not dry:
            if not (cont and cont.startswith("https:")):
                raise RuntimeError("the checkout has no https continue_url")
            print("continue_url (for the buyer only; not logged): " + cont, flush=True)
    finally:
        if checkout_id and endpoint:
            try:
                call("cancel_checkout", "POST", endpoint, {"Authorization": "Bearer " + (TOKEN[0] if TOKEN else "")},
                     {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "cancel_checkout", "arguments": {
                         "meta": dict(meta, **{"idempotency-key": str(uuid.uuid4())}), "id": checkout_id}}}, dry)
                say("canceled")
            except Exception as e:     # a checkout left open must be loud
                say("WARNING: a checkout %s may be left open: %s" % (checkout_id, e))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
