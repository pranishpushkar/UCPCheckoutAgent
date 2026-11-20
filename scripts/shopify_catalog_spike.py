"""Real catalog, stage 0 (notes/real-catalog-proposal.md): a READ-ONLY spike of Shopify's Global Catalog MCP, to settle
VERIFY C1-C6 before any DESIGN change. Python 3 standard library only; run with `python3 -I` through
scripts/shopify-catalog-spike.sh. Never run in CI or by an agent.

It reads the agent profile (C6), takes one token-tier token (C5), lists the catalog's tools (C1, C2), searches twice
(IN/INR and US/USD, each filtered to available items that ship to that country; with --pages N, up to N pages of the
IN/INR search, following its cursor), searches once more anonymously (C4),
looks the first offers up, and reads each with get_product (C1, C3). It calls only three hosts: the agent profile's
(GET), the token endpoint and the catalog (POST), and only tools/list, search_catalog, lookup_catalog and get_product.
It never calls a store, never builds or follows a checkout_url, and never sends a buyer IP. Every printed or recorded
line goes through the H5 spike's redact(); recordings are sanitized, with every checkout_url, url and seller URL cut to
its host."""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shopify_spike as spike  # noqa: E402  (the H5 spike's redaction, sanitizing and test routing, reused as they are)

CATALOG = "https://catalog.shopify.com/api/ucp/mcp"
TOKEN_URL = "https://api.shopify.com/auth/access_token"
POST_HOSTS = ("catalog.shopify.com", "api.shopify.com")
TOOLS = ("search_catalog", "lookup_catalog", "get_product")
CONTEXTS = (("IN", "INR"), ("US", "USD"))
CAPABILITIES = ("dev.ucp.shopping.catalog.search", "dev.ucp.shopping.catalog.lookup", "dev.shopify.catalog.global")
DEFAULT_RECORD = "notes/catalog/recordings"
DEFAULT_QUERY = "snowboard"
MAX_PAGES = 3                   # --pages: how many pages of the IN/INR search to read (buyer country)
GAP = 0.5                       # seconds between calls: far below the documented 5 requests per second
HEADER_HINTS = re.compile(r"rate|retry", re.I)
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def say(line):
    spike.say(line)


def host_only(url):
    return spike.host_only(url)


def strip_urls(value, key=None):
    """Every url, checkout_url and seller url cut to its host (their paths and queries carry the variant and the
    attribution), any other http(s) string with a query kept without it, JSON in a string walked too; then the H5
    spike's sanitize()."""
    if isinstance(value, dict):
        return {k: strip_urls(v, k) for k, v in value.items()}
    if isinstance(value, list):
        return [strip_urls(v) for v in value]
    if isinstance(value, str):
        if value.strip()[:1] in ("{", "["):
            try:
                return json.dumps(strip_urls(json.loads(value)), sort_keys=True)
            except ValueError:
                pass
        if value.startswith(("http://", "https://")):
            if key in ("url", "checkout_url"):
                return host_only(value)
            if "?" in value:
                return value.split("?", 1)[0] + "?<redacted>"
    return value


def sanitize(value):
    return spike.sanitize(strip_urls(value))


def record(directory, name, value):
    if not directory:
        return
    with open(os.path.join(directory, name + ".json"), "w") as f:
        f.write(spike.redact(json.dumps(sanitize(value), indent=2, sort_keys=True)) + "\n")


def allowed(method, url, profile_url):
    p = urllib.parse.urlparse(url)
    if p.scheme != "https" or not p.hostname:
        return False
    if method == "GET":
        return url == profile_url
    return method == "POST" and p.hostname in POST_HOSTS


def call(step, method, url, headers, body, dry, profile_url, record_as=None, directory=None):
    """One read-only call, or its dry-run line. Returns (status, headers, answer). Refuses any call outside the spike's
    three hosts, and any tool but the catalog's three."""
    if not allowed(method, url, profile_url):
        raise SystemExit("refused: %s %s is not one of the spike's read-only calls" % (method, host_only(url)))
    if isinstance(body, dict) and body.get("method") == "tools/call" \
            and (body.get("params") or {}).get("name") not in TOOLS:
        raise SystemExit("refused: only %s are called" % ", ".join(TOOLS))
    shown = {k: ("<redacted>" if k.lower() == "authorization" else v) for k, v in headers.items()}
    if dry:
        say("DRY-RUN %s: %s %s headers=%s body=%s" % (step, method, url, json.dumps(shown),
                                                       json.dumps(body) if body is not None else "none"))
        return 0, {}, {}
    req = urllib.request.Request(spike.url_for(url), data=json.dumps(body).encode() if body is not None else None,
                                 method=method, headers=dict(headers, **{"Content-Type": "application/json"}))
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            status, got, text = r.status, dict(r.headers.items()), r.read().decode()
    except urllib.error.HTTPError as e:
        status, got, text = e.code, dict(e.headers.items()), e.read().decode()
    except urllib.error.URLError as e:
        raise RuntimeError("%s: no answer (%s)" % (step, e.reason))
    say("%s: HTTP %s" % (step, status))
    try:
        answer = json.loads(text) if text else {}
    except ValueError:
        answer = {"unreadable": text[:500]}
    if record_as:
        record(directory, record_as, {"http_status": status, "answer": answer})
    if GAP:
        time.sleep(GAP)
    return status, got, answer


def rpc(n, name, catalog, meta):
    return {"jsonrpc": "2.0", "id": n, "method": "tools/call",
            "params": {"name": name, "arguments": {"meta": meta, "catalog": catalog}}}


def offers(answer):
    """Every (product, variant) pair of an answer: products[] or a single product."""
    payload = spike.content(answer)
    products = payload.get("products") or ([payload["product"]] if isinstance(payload.get("product"), dict) else [])
    for product in products:
        if isinstance(product, dict):
            for v in product.get("variants") or []:
                if isinstance(v, dict):
                    yield product, v


def seller_of(v):
    s = v.get("seller") if isinstance(v.get("seller"), dict) else {}
    return str(s.get("name") or "?"), str(s.get("domain") or "?").lower()


def plural(n, word):
    return "%d %s%s" % (n, word, "" if n == 1 else "s")


def context_summary(answer):
    payload = spike.content(answer)
    products = {p.get("id") for p, _ in offers(answer)}
    variants = [v for _, v in offers(answer)]
    sellers = sorted({seller_of(v) for v in variants})
    currencies = sorted({str((v.get("price") or {}).get("currency")) for v in variants if isinstance(v.get("price"), dict)})
    messages = [m.get("code") for m in payload.get("messages") or [] if isinstance(m, dict)]
    pagination = payload.get("pagination") if isinstance(payload.get("pagination"), dict) else {}
    return {"products": len(products), "offers": len(variants), "sellers": ["%s (%s)" % s for s in sellers],
            "currencies": currencies, "messages": messages, "total_count": pagination.get("total_count"),
            "has_next_page": pagination.get("has_next_page"), "ucp_version": (payload.get("ucp") or {}).get("version")}


def page_counts(answer):
    """One page's offers counted by their price's currency."""
    counts = {}
    for _, v in offers(answer):
        currency = str((v.get("price") or {}).get("currency")) if isinstance(v.get("price"), dict) else "none"
        counts[currency] = counts.get(currency, 0) + 1
    return counts


def currency_line(label, counts):
    total = sum(counts.values())
    other = {k: n for k, n in sorted(counts.items()) if k != "INR"}
    return "%s: %s; INR %d; other currencies %d%s" % (
        label, plural(total, "offer"), counts.get("INR", 0), sum(other.values()),
        " (%s)" % ", ".join("%s %d" % kv for kv in other.items()) if other else "")


def pages_option(argv):
    value = option(argv, "--pages", "1")
    if not value.isdigit() or not 1 <= int(value) <= MAX_PAGES:
        raise SystemExit("refused: --pages is 1 to %d" % MAX_PAGES)
    return int(value)


def link_shape(url):
    p = urllib.parse.urlparse(url)
    return re.sub(r"\d+", "<n>", p.path), sorted(urllib.parse.parse_qs(p.query, keep_blank_values=True))


def schema_has(value, name):
    if isinstance(value, dict):
        return name in (value.get("properties") or {}) or any(schema_has(v, name) for v in value.values())
    if isinstance(value, list):
        return any(schema_has(v, name) for v in value)
    return False


def declared(value, out):
    """Every capability name a profile declares: a dev.* key, or a dev.* "name" value, anywhere in it."""
    if isinstance(value, dict):
        for k, v in value.items():
            if isinstance(k, str) and k.startswith("dev."):
                out.add(k)
            if k == "name" and isinstance(v, str) and v.startswith("dev."):
                out.add(v)
            declared(v, out)
    elif isinstance(value, list):
        for v in value:
            declared(v, out)
    return out


def configuration(env, dry):
    missing = [v for v in ("SHOPIFY_CLIENT_ID", "SHOPIFY_CLIENT_SECRET", "AGENT_PROFILE_URL") if not env.get(v)]
    if missing and not dry:
        raise SystemExit("missing environment: " + ", ".join(missing))
    profile = env.get("AGENT_PROFILE_URL") or "https://<AGENT_PROFILE_URL>/"
    p = urllib.parse.urlparse(profile)
    if p.scheme != "https" or not p.hostname or p.hostname.endswith(".myshopify.com"):
        raise SystemExit("refused: AGENT_PROFILE_URL must be https and not on a store (the spike calls no store)")
    return profile


def option(argv, name, default):
    return argv[argv.index(name) + 1] if name in argv else default


def main(argv):
    dry = "--dry-run" in argv
    pages = pages_option(argv)
    profile = configuration(os.environ, dry)
    query = option(argv, "--query", DEFAULT_QUERY)
    directory = None
    if not dry:
        directory = os.path.abspath(option(argv, "--record", os.path.join(ROOT, DEFAULT_RECORD)))
        os.makedirs(directory, exist_ok=True)
    meta = {"ucp-agent": {"profile": profile}}
    answers = []          # every catalog answer, for C1-C3
    report = {"contexts": {}}

    status, _, prof = call("agent profile", "GET", profile, {}, None, dry, profile, "agent_profile", directory)
    caps = declared(prof, set())
    report["C6"] = {c: c in caps for c in CAPABILITIES}

    status, _, tok = call("token", "POST", TOKEN_URL, {}, {
        "client_id": os.environ.get("SHOPIFY_CLIENT_ID", "<client id>"),
        "client_secret": os.environ.get("SHOPIFY_CLIENT_SECRET", "<client secret>"),
        "grant_type": "client_credentials"}, dry, profile)
    token_status = status
    if tok.get("access_token"):
        spike.TOKEN.append(tok["access_token"])
    authed = dry or bool(spike.TOKEN)
    auth = {"Authorization": "Bearer " + (spike.TOKEN[0] if spike.TOKEN else "<token>")}

    tools = {}
    first = {}
    search_status = None
    if authed:
        _, _, tools = call("tools/list", "POST", CATALOG, auth, {"jsonrpc": "2.0", "id": 1, "method": "tools/list"},
                           dry, profile, "tools_list", directory)
        for n, (country, currency) in enumerate(CONTEXTS, start=2):
            label = "%s/%s" % (country, currency)
            status, _, found = call("search_catalog " + label, "POST", CATALOG, auth, rpc(n, "search_catalog", {
                "query": query, "context": {"address_country": country, "currency": currency},
                "filters": {"available": True, "ships_to": {"country": country}}, "pagination": {"limit": 10}}, meta),
                dry, profile, "search_%s_%s" % (country.lower(), currency.lower()), directory)
            search_status = search_status or status
            answers.append(found)
            report["contexts"][label] = context_summary(found)
            if country == "IN" and pages > 1:
                report["contexts"][label]["pages"] = [{"page": 1, "currencies": page_counts(found)}]
                answer = found
                for page in range(2, pages + 1):
                    cursor = ((spike.content(answer).get("pagination") or {}) if not dry else {}).get("cursor")
                    more = (spike.content(answer).get("pagination") or {}).get("has_next_page") if not dry else True
                    if not more or not (cursor or dry):
                        break
                    status, _, answer = call("search_catalog %s page %d" % (label, page), "POST", CATALOG, auth, rpc(
                        n, "search_catalog", {
                            "query": query, "context": {"address_country": country, "currency": currency},
                            "filters": {"available": True, "ships_to": {"country": country}},
                            "pagination": {"limit": 10, "cursor": cursor or "<cursor of page %d>" % (page - 1)}}, meta),
                        dry, profile, "search_%s_%s_page%d" % (country.lower(), currency.lower(), page), directory)
                    answers.append(answer)
                    report["contexts"][label]["pages"].append({"page": page, "currencies": page_counts(answer)})
            ids = [v.get("id") for _, v in offers(found) if v.get("id")]
            if ids:
                first[label] = ids[0]

    status, got, anon = call("search_catalog US/USD, anonymous", "POST", CATALOG, {}, rpc(4, "search_catalog", {
        "query": query, "context": {"address_country": "US", "currency": "USD"},
        "filters": {"available": True, "ships_to": {"country": "US"}}, "pagination": {"limit": 10}}, meta),
        dry, profile, "search_us_usd_anonymous", directory)
    answers.append(anon)
    report["C4"] = {"http_status": status, "headers": {k.lower(): v for k, v in sorted(got.items())
                                                       if HEADER_HINTS.search(k)}}

    if authed:
        ids = list(first.values()) or (["<variant id from each search>"] if dry else [])
        if ids:
            _, _, looked = call("lookup_catalog", "POST", CATALOG, auth, rpc(5, "lookup_catalog", {"ids": ids}, meta),
                                dry, profile, "lookup_catalog", directory)
            answers.append(looked)
        for n, (country, currency) in enumerate(CONTEXTS, start=6):
            label = "%s/%s" % (country, currency)
            vid = first.get(label) or ("<first variant id of the %s search>" % label if dry else None)
            if vid:
                _, _, product = call("get_product " + label, "POST", CATALOG, auth, rpc(n, "get_product", {
                    "id": vid, "context": {"address_country": country, "currency": currency}}, meta),
                    dry, profile, "get_product_%s_%s" % (country.lower(), currency.lower()), directory)
                answers.append(product)

    if dry:
        say("DRY-RUN record every answer above, sanitized and redacted, under %s/ (the token answer never); then print "
            "the report: for each context its products, offers and sellers, and a line for each of C1-C6"
            % DEFAULT_RECORD)
        return 0

    every = [v for a in answers for _, v in offers(a)]
    shapes = sorted({link_shape(v["checkout_url"])[0] for v in every if isinstance(v.get("checkout_url"), str)})
    keys = sorted({k for v in every if isinstance(v.get("checkout_url"), str) for k in link_shape(v["checkout_url"])[1]})
    quantities = sorted({m.group(1) for v in every if isinstance(v.get("checkout_url"), str)
                         for m in [re.search(r":(\d+)(?:/|$)", urllib.parse.urlparse(v["checkout_url"]).path)] if m})
    tool_list = ((tools.get("result") or {}).get("tools") or []) if isinstance(tools, dict) else []
    report["C1"] = {"quantity_in_schemas": schema_has([t.get("inputSchema") for t in tool_list], "quantity"),
                    "paths": shapes, "quantities": quantities, "query_keys": keys}
    native = [((v.get("eligible") or {}).get("native_checkout")) for v in every if isinstance(v.get("eligible"), dict)]
    report["C2"] = {"true": native.count(True), "false": native.count(False),
                    "described_in_tools": any("native_checkout" in json.dumps(t) for t in tool_list)}
    linked = [v for v in every if isinstance(v.get("checkout_url"), str)]
    off = sorted({(urllib.parse.urlparse(v["checkout_url"]).hostname, seller_of(v)[1]) for v in linked
                  if (urllib.parse.urlparse(v["checkout_url"]).hostname or "").lower() != seller_of(v)[1]})
    report["C3"] = {"on_seller_domain": len(linked) - sum(1 for v in linked
                                                          if (urllib.parse.urlparse(v["checkout_url"]).hostname or "")
                                                          .lower() != seller_of(v)[1]),
                    "of": len(linked), "off": ["%s for %s" % pair for pair in off]}
    report["C5"] = {"token_http_status": token_status, "search_http_status": search_status}

    say("")
    say("REPORT (query %r)" % query)
    for label, summary in report["contexts"].items():
        say("%s: %s, %s, %s: %s" % (label, plural(summary["products"], "product"), plural(summary["offers"], "offer"),
                                    plural(len(summary["sellers"]), "seller"), ", ".join(summary["sellers"]) or "none"))
        say("  currencies %s; total_count %s; has_next_page %s; ucp.version %s; messages %s" % (
            ", ".join(summary["currencies"]) or "none", summary["total_count"], summary["has_next_page"],
            summary["ucp_version"], ", ".join(map(str, summary["messages"])) or "none"))
        if summary.get("pages"):
            together = {}
            for page in summary["pages"]:
                say("  " + currency_line("%s page %d" % (label, page["page"]), page["currencies"]))
                for k, v in page["currencies"].items():
                    together[k] = together.get(k, 0) + v
            say("  " + currency_line("%s pages 1-%d" % (label, len(summary["pages"])), together))
    c1 = report["C1"]
    say("C1 quantity: %s; checkout_url paths seen: %s with quantity %s; query keys: %s" % (
        "a quantity argument in the tools' input schemas" if c1["quantity_in_schemas"]
        else "no quantity argument in the tools' input schemas",
        ", ".join(c1["paths"]) or "none", ", ".join(c1["quantities"]) or "none", ", ".join(c1["query_keys"]) or "none"))
    say("C2 native_checkout (every offer in every answer): true %d, false %d%s" % (
        report["C2"]["true"], report["C2"]["false"],
        "; the tools' descriptions mention it (see tools_list.json)" if report["C2"]["described_in_tools"] else ""))
    c3 = report["C3"]
    say("C3 checkout_url on seller.domain: %d of %d%s" % (c3["on_seller_domain"], c3["of"],
                                                         "; off it: " + ", ".join(c3["off"]) if c3["off"] else ""))
    c4 = report["C4"]
    say("C4 anonymous search: HTTP %s%s" % (c4["http_status"], "".join("; %s: %s" % kv for kv in c4["headers"].items())))
    say("C5 key: token HTTP %s; %s" % (token_status, "authenticated search HTTP %s" % search_status if spike.TOKEN
                                       else "authenticated calls skipped"))
    say("C6 profile declares: " + ", ".join("%s %s" % (c, "yes" if ok else "no") for c, ok in report["C6"].items()))
    record(directory, "report", report)
    say("recorded under %s" % directory)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
