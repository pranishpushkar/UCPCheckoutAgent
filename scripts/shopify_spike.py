"""The Shopify spike of H1 C11, fixed for H5 Stage 0 (docs/interop/shopify-spike.md): one token, one read-only search of the
global catalog, the store's profile, one read-only search at the store itself, ONE checkout at the owner's own development
store with an item from that store's search, then cancel. Python 3 standard library only; run with `python3 -I`.
It never completes, never sends a card, a payment object or an address, and sends the buyer's IP only with --with-buyer-ip.
Secrets come from the environment only and are never printed: every printed or recorded line goes through redact().
Never run in CI or by an agent."""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

SECRET_VARS = ("SHOPIFY_CLIENT_ID", "SHOPIFY_CLIENT_SECRET", "BUYER_IP")
TOKEN = []          # the access token, once there is one (memory only)
RECORD = [None]     # the directory --record writes the answers to, if any
TEST_BASE = os.environ.get("SHOPIFY_SPIKE_TEST_BASE")   # tests only: every call goes to this local URL
DEFAULT_QUERY = "snowboard"   # the dev store's generated test data has snowboards
URL_FIELDS = ("continue_url", "checkout_url")   # the path and query of a checkout URL open that checkout


def secrets():
    return [os.environ[v] for v in SECRET_VARS if os.environ.get(v)] + TOKEN


def redact(text):
    for s in secrets():
        text = text.replace(s, "<redacted>")
    return text


def say(line):
    print(redact(line), flush=True)


def url_for(url):
    """In tests every call goes to the local fake as /<host>/<path>, so the fake can answer per host."""
    if TEST_BASE:
        p = urllib.parse.urlparse(url)
        return "%s/%s%s" % (TEST_BASE.rstrip("/"), p.hostname, p.path)
    return url


def host_only(url):
    p = urllib.parse.urlparse(url)
    return "%s://%s/..." % (p.scheme, p.hostname) if p.hostname else "<none>"


def sanitize(value, key=None):
    """An answer made safe to keep: walked recursively, JSON held in a string (Shopify sends each payload twice, once as
    a JSON string in result.content[].text) decoded and walked too, continue_url and checkout_url cut to their host, and
    any "?key=" (Shopify's checkout ids and URLs carry an access key) masked."""
    if isinstance(value, dict):
        return {k: sanitize(v, k) for k, v in value.items()}
    if isinstance(value, list):
        return [sanitize(v) for v in value]
    if isinstance(value, str):
        if key in URL_FIELDS:
            return host_only(value)
        if value.strip()[:1] in ("{", "["):
            try:
                return json.dumps(sanitize(json.loads(value)), sort_keys=True)
            except ValueError:
                pass
        if "?key=" in value:
            return value.split("?key=", 1)[0] + "?key=<redacted>"
    return value


def record(name, answer):
    """Keeps an answer for the spike report: sanitized and redacted. The token answer is never recorded."""
    if not RECORD[0]:
        return
    with open(os.path.join(RECORD[0], name + ".json"), "w") as f:
        f.write(redact(json.dumps(sanitize(answer), indent=2, sort_keys=True)) + "\n")


def content(answer):
    """The payload of an answer: Shopify's MCP puts it in result.structuredContent; an older or plain answer has it in
    result, or at the top level."""
    if not isinstance(answer, dict):
        return {}
    result = answer.get("result")
    if isinstance(result, dict):
        inner = result.get("structuredContent")
        return inner if isinstance(inner, dict) else result
    return answer


def profile_endpoint(profile_doc):
    """The MCP endpoint a store's /.well-known/ucp names: ucp.services["dev.ucp.shopping"], the entry whose transport is
    mcp (the shape ucplab-dev's profile had on 2026-11-16). An older top-level mcp_endpoint is still read. None if absent."""
    services = ((profile_doc.get("ucp") or {}).get("services") or {}).get("dev.ucp.shopping") or []
    for service in services:
        if isinstance(service, dict) and service.get("transport") == "mcp" and service.get("endpoint"):
            return service["endpoint"]
    return profile_doc.get("mcp_endpoint")


def first_item(found, domain):
    """The first variant id of the store's own search, skipping any variant whose seller is another store."""
    for product in found.get("products") or []:
        for variant in product.get("variants") or []:
            seller = (variant.get("seller") or {}).get("domain")
            if variant.get("id") and (seller is None or seller.lower() == domain):
                return variant["id"]
    return None


def call(step, method, url, headers, body, dry, record_as=None):
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
        if record_as:
            record(record_as + ".error", {"http_status": status, "body": text})
        say("%s answered (redacted, first 500 characters): %s" % (step, text[:500]))
        raise RuntimeError("%s answered HTTP %s" % (step, status))
    answer = json.loads(text) if text else {}
    if record_as:
        record(record_as, answer)
    return answer


def configuration(env, with_ip):
    needed = ["SHOPIFY_CLIENT_ID", "SHOPIFY_CLIENT_SECRET", "SHOPIFY_STORE_DOMAIN", "AGENT_PROFILE_URL"]
    if with_ip:
        needed.append("BUYER_IP")
    missing = [v for v in needed if not env.get(v)]
    if missing:
        raise SystemExit("missing environment: " + ", ".join(missing))
    return env["SHOPIFY_STORE_DOMAIN"].strip().lower()


def main(argv):
    dry = "--dry-run" in argv
    with_ip = "--with-buyer-ip" in argv
    RECORD[0] = None
    if "--record" in argv:
        RECORD[0] = argv[argv.index("--record") + 1]
        os.makedirs(RECORD[0], exist_ok=True)
    domain = configuration(os.environ, with_ip)
    if "--i-own-this-store" not in argv:
        raise SystemExit("refused: pass --i-own-this-store to state that %s is a development store you own (DESIGN 26.5)" % domain)
    query = os.environ.get("SPIKE_QUERY") or DEFAULT_QUERY
    meta = {"ucp-agent": {"profile": os.environ["AGENT_PROFILE_URL"]}}
    checkout_id = None
    endpoint = None
    store_headers = {}
    try:
        tok = call("token", "POST", "https://api.shopify.com/auth/access_token", {},
                   {"client_id": os.environ["SHOPIFY_CLIENT_ID"], "client_secret": os.environ["SHOPIFY_CLIENT_SECRET"],
                    "grant_type": "client_credentials"}, dry)
        TOKEN.append(tok.get("access_token", "")) if tok.get("access_token") else None
        auth = {"Authorization": "Bearer " + (TOKEN[0] if TOKEN else "<token>")}
        search = {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "search_catalog",
                  "arguments": {"meta": meta, "catalog": {"query": query}}}}
        call("catalog search (global, read-only)", "POST", "https://catalog.shopify.com/api/ucp/mcp", auth, search, dry,
             "catalog_search_global")
        profile_doc = call("store profile", "GET", "https://%s/.well-known/ucp" % domain, {}, None, dry, "store_profile")
        if not dry:
            ucp = profile_doc.get("ucp") or {}
            say("store profile: version %s, supported %s, payment handlers %s" % (
                ucp.get("version"), ", ".join(sorted(ucp.get("supported_versions") or {})) or "none",
                ", ".join(sorted(ucp.get("payment_handlers") or {})) or "none"))
        endpoint = profile_endpoint(profile_doc) or ("https://%s/api/ucp/mcp" % domain if dry else None)
        if not endpoint:
            raise SystemExit("stopped: the store's profile names no MCP endpoint in ucp.services")
        host = urllib.parse.urlparse(endpoint).hostname
        if not dry and host != domain:
            raise SystemExit("refused: the profile's MCP endpoint host %s is not the stated store %s (DESIGN 26.5)" % (host, domain))
        # Every call to the store carries the buyer IP when asked (its endpoint refuses even a search without it); the
        # global catalog's calls never do.
        store_headers = dict(auth, **({"Shopify-Buyer-IP": os.environ["BUYER_IP"]} if with_ip else {}))
        found = call("catalog search (the store, read-only)", "POST", endpoint, store_headers, search, dry, "catalog_search_store")
        item = first_item(content(found), domain)
        if not item:
            if not dry:
                raise SystemExit("stopped: the store's own search for %r found no product, so nothing goes in a checkout" % query)
            item = "<item id from the store's own search>"
        created = call("create_checkout", "POST", endpoint, store_headers,
                       {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "create_checkout", "arguments": {
                           "meta": meta, "checkout": {"currency": "USD", "line_items": [{"item": {"id": item}, "quantity": 1}]}}}},
                       dry, "create_checkout")
        checkout = content(created)
        checkout_id = checkout.get("id")
        if not dry and not checkout_id:
            say("WARNING: create_checkout succeeded but named no checkout id, so a checkout may be open at %s and was not "
                "canceled. Cancel it by hand. The answer (redacted, first 2000 characters): %s" % (domain, json.dumps(created)[:2000]))
            raise RuntimeError("no checkout id in the create answer")
        cont = checkout.get("continue_url")
        if not dry:
            say("checkout status: %s" % checkout.get("status"))
            if not (cont and cont.startswith("https:")):
                raise RuntimeError("the checkout has no https continue_url")
            print("continue_url (for the buyer only; not logged): " + cont, flush=True)
    finally:
        if checkout_id and endpoint:
            try:
                canceled = call("cancel_checkout", "POST", endpoint, store_headers,
                                {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "cancel_checkout", "arguments": {
                                    "meta": dict(meta, **{"idempotency-key": str(uuid.uuid4())}), "id": checkout_id}}},
                                dry, "cancel_checkout")
                say("canceled (status: %s)" % content(canceled).get("status"))
            except Exception as e:     # a checkout left open must be loud
                say("WARNING: a checkout %s may be left open: %s" % (checkout_id, e))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
