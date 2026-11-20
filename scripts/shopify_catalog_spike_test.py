"""Tests of shopify_catalog_spike.py against a LOCAL FAKE server (never Shopify). Run by hand:
python3 -I scripts/shopify_catalog_spike_test.py. Not part of ./mvnw verify (verify needs no Python)."""
import http.server
import io
import json
import os
import shutil
import sys
import tempfile
import threading
import unittest
from contextlib import redirect_stdout

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import shopify_spike as spike  # noqa: E402
try:
    import shopify_catalog_spike as c  # noqa: E402
except ImportError:     # red before the script exists: each test fails on its first assertion
    c = None

PROFILE = "https://agent-profile.example/agent.json"
CANARIES = {"SHOPIFY_CLIENT_ID": "canary-id-111", "SHOPIFY_CLIENT_SECRET": "canary-secret-222",
            "AGENT_PROFILE_URL": PROFILE}
TOKEN = "canary-token-333"
ATTRIBUTION = "canary-attribution-444"
CATALOG = "catalog.shopify.com"


def variant(vid, seller, domain, currency, link_host=None, native=True):
    return {"id": vid, "price": {"amount": 69995, "currency": currency},
            "url": "https://%s/products/board?variant=%s&_ucp=%s" % (domain, vid[-3:], ATTRIBUTION),
            "checkout_url": "https://%s/buy/%s:1?_ucp=%s" % (link_host or domain, vid[-3:], ATTRIBUTION),
            "availability": {"available": True}, "eligible": {"native_checkout": native},
            "seller": {"name": seller, "domain": domain, "url": "https://%s/?ref=%s" % (domain, ATTRIBUTION)}}


class Fake(http.server.BaseHTTPRequestHandler):
    """Every call arrives as /<host>/<path> (shopify_spike.url_for in test mode), so the fake answers per host."""
    calls = []          # (method, host, rpc method or tool name, headers with lower-cased keys, body)
    token_status = 200
    profile_caps = ("dev.ucp.shopping.catalog.search", "dev.ucp.shopping.catalog.lookup", "dev.shopify.catalog.global")
    other_link_host = False

    def split(self):
        host, _, path = self.path.lstrip("/").partition("/")
        return host, "/" + path

    def do_GET(self):
        host, path = self.split()
        Fake.calls.append(("GET", host, path, {k.lower(): v for k, v in self.headers.items()}, None))
        self.reply({"ucp": {"version": "2026-08-25", "capabilities": {name: [{"version": "2026-08-25"}]
                                                                       for name in Fake.profile_caps}}})

    def do_POST(self):
        host, path = self.split()
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        headers = {k.lower(): v for k, v in self.headers.items()}
        name = body.get("params", {}).get("name") or body.get("method") or path
        Fake.calls.append(("POST", host, name, headers, body))
        if path == "/auth/access_token":
            self.reply({"access_token": TOKEN} if Fake.token_status == 200 else {"error": "invalid_client"},
                       Fake.token_status)
        elif name == "tools/list":
            self.reply({"jsonrpc": "2.0", "id": body.get("id"), "result": {"tools": [
                {"name": "search_catalog", "description": "Search products.", "inputSchema": {"type": "object"}},
                {"name": "get_product", "description": "Variants carry eligible.native_checkout.",
                 "inputSchema": {"type": "object", "properties": {"catalog": {"type": "object", "properties": {
                     "id": {"type": "string"}}}}}}]}})
        elif name == "search_catalog":
            country = body["params"]["arguments"]["catalog"]["context"]["address_country"]
            if country == "IN":
                products = [{"id": "gid://shopify/p/in1", "title": "Board", "variants": [
                    variant("gid://shopify/ProductVariant/101", "Mumbai Boards", "mumbai-boards.example", "INR")]}]
            else:
                products = [{"id": "gid://shopify/p/us1", "title": "Board", "variants": [
                    variant("gid://shopify/ProductVariant/201", "Snow Co", "snow-co.example", "USD"),
                    variant("gid://shopify/ProductVariant/202", "Peak Shop", "peak.example", "USD", native=False)]}]
            extra = {"Retry-After": "1", "X-RateLimit-Limit": "2"} if "authorization" not in headers else {}
            self.mcp({"ucp": {"version": "2026-08-25", "capabilities": {"dev.ucp.shopping.catalog.search": []}},
                      "products": products, "pagination": {"has_next_page": False, "total_count": len(products)}},
                     extra)
        elif name in ("lookup_catalog", "get_product"):
            vid = (body["params"]["arguments"]["catalog"].get("id")
                   or body["params"]["arguments"]["catalog"]["ids"][0])
            link_host = "elsewhere.example" if Fake.other_link_host else None
            v = variant(vid, "Snow Co", "snow-co.example", "USD", link_host)
            product = {"id": "gid://shopify/p/x", "title": "Board", "variants": [v]}
            self.mcp({"product": product} if name == "get_product" else {"products": [product]})
        else:
            self.reply({"error": "unexpected"}, 404)

    def mcp(self, payload, extra=None):
        self.reply({"jsonrpc": "2.0", "id": 1, "result": {"content": [{"type": "text", "text": json.dumps(payload)}],
                                                         "isError": False, "structuredContent": payload}}, 200, extra)

    def reply(self, obj, status=200, extra=None):
        b = json.dumps(obj).encode()
        self.send_response(status)
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def log_message(self, *a):
        pass


class CatalogSpikeTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(c, "scripts/shopify_catalog_spike.py exists")
        Fake.calls = []
        Fake.token_status = 200
        Fake.profile_caps = ("dev.ucp.shopping.catalog.search", "dev.ucp.shopping.catalog.lookup",
                             "dev.shopify.catalog.global")
        Fake.other_link_host = False
        self.server = http.server.HTTPServer(("127.0.0.1", 0), Fake)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.saved = dict(os.environ)
        os.environ.update(CANARIES)
        spike.TEST_BASE = "http://127.0.0.1:%d" % self.server.server_address[1]
        spike.TOKEN.clear()
        c.GAP = 0
        self.record = tempfile.mkdtemp()

    def tearDown(self):
        self.server.shutdown()
        os.environ.clear()
        os.environ.update(self.saved)
        spike.TEST_BASE = None
        shutil.rmtree(self.record)

    def run_main(self, args):
        out = io.StringIO()
        with redirect_stdout(out):
            try:
                c.main(args)
            except (SystemExit, RuntimeError) as e:
                out.write("EXIT %s\n" % e)
        return out.getvalue()

    def real(self):
        return self.run_main(["--record", self.record])

    def tools(self):
        return [call[2] for call in Fake.calls if call[0] == "POST" and call[1] == CATALOG]

    # Covers: 26.7 stage 0, 26.4, I8 (the dry run prints every call with the secrets replaced, and calls nothing)
    def test_theDryRun_printsEveryCall_andNoSecret_andCallsNothing(self):
        out = self.run_main(["--dry-run"])
        for step in ("agent profile", "token", "tools/list", "search_catalog IN/INR", "search_catalog US/USD",
                     "search_catalog US/USD, anonymous", "lookup_catalog", "get_product IN/INR", "get_product US/USD"):
            self.assertIn("DRY-RUN " + step, out)
        for secret in ("canary-id-111", "canary-secret-222", TOKEN):
            self.assertNotIn(secret, out)
        self.assertIn('"Authorization": "<redacted>"', out)
        self.assertEqual(Fake.calls, [])

    # Covers: 26.7, 26.5, I12, N1 (read only: the only hosts called are the agent profile's, the token endpoint and
    # the catalog; no store; only tools/list and the three catalog tools; no buyer IP on any call)
    def test_aRun_callsTheCatalogOnly_readOnly_andNeverAStore(self):
        self.real()
        hosts = {call[1] for call in Fake.calls}
        self.assertEqual(hosts, {"agent-profile.example", "api.shopify.com", CATALOG})
        self.assertEqual(set(self.tools()), {"tools/list", "search_catalog", "lookup_catalog", "get_product"})
        for call in Fake.calls:
            self.assertNotIn("shopify-buyer-ip", call[3])
        self.assertFalse(any(h.endswith(".myshopify.com") for h in hosts))

    # Covers: 26.7, D7 (the search runs twice, IN/INR and US/USD, each filtered to available and ships_to its country,
    # authenticated, with the agent profile in meta; a third, anonymous, US search has no Authorization)
    def test_theSearches_carryTheContextsAndFilters(self):
        self.real()
        searches = [call for call in Fake.calls if call[2] == "search_catalog"]
        self.assertEqual(len(searches), 3)
        seen = []
        for call in searches:
            args = call[4]["params"]["arguments"]
            self.assertEqual(args["meta"], {"ucp-agent": {"profile": PROFILE}})
            cat = args["catalog"]
            country = cat["context"]["address_country"]
            self.assertEqual(cat["filters"], {"available": True, "ships_to": {"country": country}})
            self.assertEqual(cat["query"], "snowboard")
            seen.append((country, cat["context"]["currency"], "authorization" in call[3]))
        self.assertEqual(seen, [("IN", "INR", True), ("US", "USD", True), ("US", "USD", False)])
        self.assertEqual(searches[0][3]["authorization"], "Bearer " + TOKEN)

    # Covers: 26.7, I8 (recordings: sanitized and redacted; checkout_url, url and seller.url cut to their host, so no
    # attribution parameter is kept; the token answer is never recorded)
    def test_theRecordings_areSanitized(self):
        self.real()
        names = sorted(os.listdir(self.record))
        for expected in ("agent_profile.json", "tools_list.json", "search_in_inr.json", "search_us_usd.json",
                         "search_us_usd_anonymous.json", "lookup_catalog.json", "get_product_in_inr.json",
                         "get_product_us_usd.json", "report.json"):
            self.assertIn(expected, names)
        self.assertFalse(any("token" in n for n in names))
        for name in names:
            with open(os.path.join(self.record, name)) as f:
                text = f.read()
            for secret in ("canary-id-111", "canary-secret-222", TOKEN, ATTRIBUTION):
                self.assertNotIn(secret, text, name)
        with open(os.path.join(self.record, "get_product_us_usd.json")) as f:
            self.assertIn('"checkout_url": "https://snow-co.example/..."', f.read())

    # Covers: 26.7 (the report: for each context the result counts and the sellers, and a line for each of C1-C6)
    def test_theReport_countsResultsAndSellers_andSettlesC1toC6(self):
        out = self.real()
        self.assertIn("IN/INR: 1 product, 1 offer, 1 seller: Mumbai Boards (mumbai-boards.example)", out)
        self.assertIn("US/USD: 1 product, 2 offers, 2 sellers: Peak Shop (peak.example), Snow Co (snow-co.example)", out)
        self.assertIn("C1 quantity: no quantity argument in the tools' input schemas; checkout_url paths seen: "
                      "/buy/<n>:<n> with quantity 1; query keys: _ucp", out)
        self.assertIn("C2 native_checkout (every offer in every answer): true 6, false 2", out)
        self.assertIn("C3 checkout_url on seller.domain: 8 of 8", out)
        self.assertIn("C4 anonymous search: HTTP 200; retry-after: 1; x-ratelimit-limit: 2", out)
        self.assertIn("C5 key: token HTTP 200; authenticated search HTTP 200", out)
        self.assertIn("C6 profile declares: dev.ucp.shopping.catalog.search yes, dev.ucp.shopping.catalog.lookup yes, "
                      "dev.shopify.catalog.global yes", out)
        with open(os.path.join(self.record, "report.json")) as f:
            report = json.load(f)
        self.assertEqual(sorted(report), ["C1", "C2", "C3", "C4", "C5", "C6", "contexts"])

    # Covers: 26.7, I12 (a checkout_url off the seller's domain is counted and named by host)
    def test_aLinkOffTheSellersDomain_isReported(self):
        Fake.other_link_host = True
        out = self.real()
        self.assertIn("C3 checkout_url on seller.domain: 5 of 8; off it: elsewhere.example for snow-co.example", out)

    # Covers: 26.7 (a profile missing a capability says so)
    def test_aProfileWithoutTheGlobalCapability_saysNo(self):
        Fake.profile_caps = ("dev.ucp.shopping.catalog.search",)
        out = self.real()
        self.assertIn("dev.ucp.shopping.catalog.lookup no, dev.shopify.catalog.global no", out)

    # Covers: 26.7, 26.4 (a key the token endpoint refuses: C5 says so, the authenticated calls are skipped, and the
    # anonymous search still runs)
    def test_aRefusedKey_isReported_andTheAnonymousSearchStillRuns(self):
        Fake.token_status = 401
        out = self.real()
        self.assertIn("C5 key: token HTTP 401; authenticated calls skipped", out)
        searches = [call for call in Fake.calls if call[2] == "search_catalog"]
        self.assertEqual(len(searches), 1)
        self.assertNotIn("authorization", searches[0][3])
        self.assertNotIn("canary-secret-222", out)

    # Covers: 26.5, I12 (the agent profile must be https and on no store; refused before any call, the dry run too)
    def test_aProfileOnAStoreOrOverHttp_isRefused(self):
        for url in ("https://some-store.myshopify.com/agent.json", "http://agent-profile.example/agent.json"):
            os.environ["AGENT_PROFILE_URL"] = url
            out = self.run_main(["--dry-run"])
            self.assertIn("EXIT refused: AGENT_PROFILE_URL must be https and not on a store", out, url)
        self.assertEqual(Fake.calls, [])

    # Covers: 26.5, I12, N1 (the guard: only GET of the agent profile and POST to the token endpoint or the catalog,
    # and only the read-only tools)
    def test_theGuard_refusesAnyOtherCall(self):
        for method, url, body in (
                ("POST", "https://some-store.myshopify.com/api/ucp/mcp", {}),
                ("GET", "https://catalog.shopify.com/api/ucp/mcp", None),
                ("POST", "http://catalog.shopify.com/api/ucp/mcp", {}),
                ("POST", "https://catalog.shopify.com/api/ucp/mcp", {"jsonrpc": "2.0", "id": 9, "method": "tools/call",
                                                                    "params": {"name": "create_checkout"}})):
            with self.assertRaises(SystemExit, msg="%s %s" % (method, url)):
                c.call("x", method, url, {}, body, False, PROFILE)
        self.assertEqual(Fake.calls, [])

    # Covers: 26.7 (the recordings default to notes/catalog/recordings/, which git ignores)
    def test_theRecordings_defaultUnderNotes(self):
        self.assertEqual(c.DEFAULT_RECORD, "notes/catalog/recordings")

    # Covers: 26.4, I8 (the wrapper reads the Keychain items only on a real run, into the environment)
    def test_theWrapper_readsTheKeychainOnlyForARealRun(self):
        with open(os.path.join(HERE, "shopify-catalog-spike.sh")) as f:
            text = f.read()
        self.assertIn('if [[ " $* " != *" --dry-run "* ]]; then', text)
        self.assertIn("-s ucplab-shopify-catalog-id ", text)
        self.assertIn("-s ucplab-shopify-catalog-secret ", text)
        self.assertIn("exec python3 -I scripts/shopify_catalog_spike.py", text)


if __name__ == "__main__":
    unittest.main()
