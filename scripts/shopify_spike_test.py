"""Tests of shopify_spike.py against a LOCAL FAKE server (never Shopify). Run by hand: python3 -I scripts/shopify_spike_test.py
Not part of ./mvnw verify (verify needs no Python)."""
import http.server
import io
import json
import os
import sys
import threading
import unittest
from contextlib import redirect_stdout

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import shopify_spike as s  # noqa: E402

CANARIES = {"SHOPIFY_CLIENT_ID": "canary-id-111", "SHOPIFY_CLIENT_SECRET": "canary-secret-222", "BUYER_IP": "203.0.113.77",
            "SHOPIFY_STORE_DOMAIN": "my-dev-store.myshopify.com", "AGENT_PROFILE_URL": "https://example.invalid/agent.json"}


STORE = "my-dev-store.myshopify.com"
GLOBAL = "catalog.shopify.com"
STORE_ITEM = "gid://shopify/ProductVariant/1"
OTHER_ITEM = "gid://shopify/ProductVariant/999"   # a real seller's item in the global catalog, never the owner's


class Fake(http.server.BaseHTTPRequestHandler):
    """Every call arrives as /<host>/<path> (shopify_spike.url_for in test mode), so the fake answers per host. Answers
    are wrapped as Shopify's MCP wraps them, in result.structuredContent, unless `flat`."""
    calls = []          # (method, host, tool name or path)
    paths = []          # the path of every POST
    headers = {}        # tool name -> the last call's headers, keys lower-cased
    bodies = {}         # tool name + "@" + host -> the last call's body
    fail_create = False
    bad_url = False
    flat = False
    no_id = False
    no_products = False
    profile_host = STORE   # the host the profile's MCP endpoint names

    def split(self):
        host, _, path = self.path.lstrip("/").partition("/")
        return host, "/" + path

    def do_GET(self):
        host, path = self.split()
        Fake.calls.append(("GET", host, path))
        # The shape ucplab-dev's /.well-known/ucp has (2026-11-16): the endpoint is in ucp.services, per transport.
        self.reply({"ucp": {"version": "2026-08-25", "payment_handlers": {}, "services": {"dev.ucp.shopping": [
            {"transport": "embedded", "version": "2026-08-25"},
            {"transport": "mcp", "version": "2026-08-25", "endpoint": "https://%s/ucp/mcp-from-profile" % Fake.profile_host}]}}})

    def do_POST(self):
        host, path = self.split()
        Fake.paths.append(path)
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        name = body.get("params", {}).get("name", path)
        Fake.calls.append(("POST", host, name))
        Fake.headers[name] = {k.lower(): v for k, v in self.headers.items()}
        Fake.bodies[name + "@" + host] = body
        if path == "/auth/access_token":
            self.reply({"access_token": "canary-token-333"})
        elif name == "search_catalog":
            item = OTHER_ITEM if host == GLOBAL else STORE_ITEM
            products = [] if Fake.no_products and host != GLOBAL else [
                {"id": "gid://shopify/p/x", "variants": [{"id": item, "seller": {"domain": host}}]}]
            self.mcp({"products": products})
        elif name == "create_checkout":
            if Fake.fail_create:
                self.send_response(500)
                self.end_headers()
                return
            url = "http://insecure.example/x" if Fake.bad_url else "https://%s/checkouts/cn/secret-path?key=canary-key-444" % STORE
            payload = {} if Fake.no_id else {"id": "chk_1", "continue_url": url, "status": "incomplete"}
            if Fake.flat:
                self.reply(payload)
            else:
                self.mcp(payload)
        else:
            self.mcp({})

    def mcp(self, content):
        self.reply({"jsonrpc": "2.0", "id": 1, "result": {"structuredContent": content}})

    def reply(self, obj):
        b = json.dumps(obj).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def log_message(self, *a):
        pass


class SpikeTest(unittest.TestCase):
    def setUp(self):
        Fake.calls, Fake.headers, Fake.bodies, Fake.paths = [], {}, {}, []
        Fake.fail_create = Fake.bad_url = Fake.flat = Fake.no_id = Fake.no_products = False
        Fake.profile_host = STORE
        self.server = http.server.HTTPServer(("127.0.0.1", 0), Fake)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        os.environ.update(CANARIES)
        os.environ["SHOPIFY_SPIKE_TEST_BASE"] = "http://127.0.0.1:%d" % self.server.server_address[1]
        s.TEST_BASE = os.environ["SHOPIFY_SPIKE_TEST_BASE"]
        s.TOKEN.clear()

    def tearDown(self):
        self.server.shutdown()

    @property
    def paths(self):
        return Fake.paths

    def names(self):
        return [c[2] for c in Fake.calls]

    def run_main(self, args):
        out = io.StringIO()
        with redirect_stdout(out):
            try:
                s.main(args)
            except (SystemExit, RuntimeError) as e:
                out.write("EXIT %s\n" % e)
        return out.getvalue()

    def test_dry_run_prints_the_plan_and_no_secret_and_makes_no_call(self):
        out = self.run_main(["--dry-run", "--i-own-this-store"])
        self.assertIn("DRY-RUN create_checkout", out)
        for canary in list(CANARIES.values())[:3] + ["canary-token-333"]:
            self.assertNotIn(canary, out)
        self.assertEqual(Fake.calls, [])

    def test_refuses_without_the_ownership_flag(self):
        out = self.run_main(["--dry-run"])
        self.assertIn("refused", out)
        self.assertEqual(Fake.calls, [])

    def test_refuses_an_endpoint_on_another_store(self):
        os.environ["SHOPIFY_STORE_DOMAIN"] = "someone-elses.myshopify.com"
        out = self.run_main(["--i-own-this-store"])
        self.assertIn("refused", out)
        self.assertNotIn("create_checkout", self.names())

    def test_a_complete_run_cancels_and_prints_no_secret(self):
        out = self.run_main(["--i-own-this-store"])
        self.assertIn("cancel_checkout", self.names())
        self.assertIn("canceled", out)
        for canary in list(CANARIES.values())[:3] + ["canary-token-333"]:
            self.assertNotIn(canary, out)

    def test_a_failure_after_the_create_still_cancels(self):
        Fake.bad_url = True
        out = self.run_main(["--i-own-this-store"])
        self.assertIn("EXIT", out)
        self.assertIn("cancel_checkout", self.names())

    def test_a_failed_create_leaves_nothing_to_cancel(self):
        Fake.fail_create = True
        self.run_main(["--i-own-this-store"])
        self.assertNotIn("cancel_checkout", self.names())

    # H5 fix 2: Shopify's MCP answers in result.structuredContent; the checkout is found there and canceled.
    def test_a_wrapped_create_is_found_and_canceled(self):
        out = self.run_main(["--i-own-this-store"])
        self.assertIn("canceled", out)
        self.assertEqual(Fake.bodies["cancel_checkout@" + STORE]["params"]["arguments"]["id"], "chk_1")

    # H5 fix 2: the older, unwrapped answer is still read.
    def test_a_flat_create_is_still_found_and_canceled(self):
        Fake.flat = True
        self.run_main(["--i-own-this-store"])
        self.assertIn("cancel_checkout", self.names())

    # H5 fix 2: a create that succeeded but names no id may have left a checkout open: say so loudly, redacted.
    def test_a_create_without_an_id_warns_loudly(self):
        Fake.no_id = True
        out = self.run_main(["--i-own-this-store"])
        self.assertIn("WARNING", out)
        self.assertIn("may be open", out)
        self.assertNotIn("canary-token-333", out)

    # H5 fix 3: the item comes from the store's own search, never from the global catalog (a real seller's item).
    def test_the_item_comes_from_the_stores_own_search(self):
        self.run_main(["--i-own-this-store"])
        self.assertIn(("POST", STORE, "search_catalog"), Fake.calls)
        line = Fake.bodies["create_checkout@" + STORE]["params"]["arguments"]["checkout"]["line_items"][0]
        self.assertEqual(line["item"]["id"], STORE_ITEM)
        self.assertLess(Fake.calls.index(("POST", STORE, "search_catalog")), Fake.calls.index(("POST", STORE, "create_checkout")))

    # H5 fix 3: no product at the store: stop before any create.
    def test_no_product_at_the_store_stops_before_the_create(self):
        Fake.no_products = True
        out = self.run_main(["--i-own-this-store"])
        self.assertIn("EXIT", out)
        self.assertNotIn("create_checkout", self.names())

    # H5 fix 4 (decision 6): no buyer data by default; BUYER_IP is not required.
    def test_no_buyer_ip_by_default(self):
        del os.environ["BUYER_IP"]
        out = self.run_main(["--i-own-this-store"])
        self.assertNotIn("missing environment", out)
        self.assertNotIn("shopify-buyer-ip", Fake.headers["create_checkout"])

    # H5 fix 4: the IP is sent only with --with-buyer-ip, and never printed.
    def test_buyer_ip_only_with_the_flag(self):
        out = self.run_main(["--i-own-this-store", "--with-buyer-ip"])
        self.assertEqual(Fake.headers["create_checkout"]["shopify-buyer-ip"], CANARIES["BUYER_IP"])
        self.assertNotIn(CANARIES["BUYER_IP"], out)

    # H5 fix 4: the flag without an IP refuses before any call.
    def test_buyer_ip_flag_without_an_ip_refuses(self):
        del os.environ["BUYER_IP"]
        out = self.run_main(["--i-own-this-store", "--with-buyer-ip"])
        self.assertIn("BUYER_IP", out)
        self.assertEqual(Fake.calls, [])

    # H5 fix 5: the query matches the dev store's generated test data.
    def test_the_query_defaults_to_snowboard(self):
        os.environ.pop("SPIKE_QUERY", None)
        self.run_main(["--i-own-this-store"])
        for host in (GLOBAL, STORE):
            self.assertEqual(Fake.bodies["search_catalog@" + host]["params"]["arguments"]["catalog"]["query"], "snowboard")

    # H5: --record keeps the answers for the spike report: redacted, no token, continue_url reduced to its host.
    def test_record_writes_redacted_answers(self):
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            self.run_main(["--i-own-this-store", "--record", d])
            names = sorted(os.listdir(d))
            self.assertIn("create_checkout.json", names)
            self.assertNotIn("token.json", names)
            text = "".join(open(os.path.join(d, n)).read() for n in names)
        for canary in list(CANARIES.values())[:3] + ["canary-token-333", "canary-key-444", "secret-path"]:
            self.assertNotIn(canary, text)
        self.assertIn(STORE, text)

    # H5 (the first real run): the MCP endpoint is the one the profile names in ucp.services, not a guess.
    def test_the_endpoint_comes_from_the_profiles_services(self):
        self.run_main(["--i-own-this-store"])
        self.assertIn(("POST", STORE, "create_checkout"), Fake.calls)
        self.assertTrue(any(c[1] == STORE and c[2] == "search_catalog" for c in Fake.calls))
        self.assertIn("/ucp/mcp-from-profile", self.paths)

    # H5 (the first real run): a profile whose MCP endpoint is on another host is refused before any call to it.
    def test_a_profile_naming_another_host_is_refused(self):
        Fake.profile_host = "someone-elses.myshopify.com"
        out = self.run_main(["--i-own-this-store"])
        self.assertIn("refused", out)
        self.assertNotIn("create_checkout", self.names())
        self.assertFalse(any(c[1] == "someone-elses.myshopify.com" for c in Fake.calls))

    # H5 fix 1: the wrapper reads the Keychain items the owner created for H5.
    def test_the_wrapper_reads_the_h5_keychain_items(self):
        text = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "shopify-spike.sh")).read()
        self.assertIn("-s ucplab-shopify-catalog-id ", text)
        self.assertIn("-s ucplab-shopify-catalog-secret ", text)
        self.assertNotIn("shopify-spike-client", text)


if __name__ == "__main__":
    unittest.main()
