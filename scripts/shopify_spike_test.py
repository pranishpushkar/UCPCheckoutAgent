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


class Fake(http.server.BaseHTTPRequestHandler):
    calls = []
    fail_create = False
    bad_url = False

    def do_GET(self):
        Fake.calls.append(("GET", self.path))
        self.reply({"mcp_endpoint": "https://my-dev-store.myshopify.com/api/ucp/mcp"})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        name = body.get("params", {}).get("name", self.path)
        Fake.calls.append(("POST", name))
        if self.path == "/auth/access_token":
            self.reply({"access_token": "canary-token-333"})
        elif name == "create_checkout":
            if Fake.fail_create:
                self.send_response(500)
                self.end_headers()
                return
            self.reply({"id": "chk_1", "continue_url": "http://insecure.example/x" if Fake.bad_url else "https://my-dev-store.myshopify.com/c/1"})
        else:
            self.reply({})

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
        Fake.calls, Fake.fail_create, Fake.bad_url = [], False, False
        self.server = http.server.HTTPServer(("127.0.0.1", 0), Fake)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        os.environ.update(CANARIES)
        os.environ["SHOPIFY_SPIKE_TEST_BASE"] = "http://127.0.0.1:%d" % self.server.server_address[1]
        s.TEST_BASE = os.environ["SHOPIFY_SPIKE_TEST_BASE"]
        s.TOKEN.clear()

    def tearDown(self):
        self.server.shutdown()

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
        self.assertNotIn(("POST", "create_checkout"), Fake.calls)

    def test_a_complete_run_cancels_and_prints_no_secret(self):
        out = self.run_main(["--i-own-this-store"])
        self.assertIn(("POST", "cancel_checkout"), Fake.calls)
        self.assertIn("canceled", out)
        for canary in list(CANARIES.values())[:3] + ["canary-token-333"]:
            self.assertNotIn(canary, out)

    def test_a_failure_after_the_create_still_cancels(self):
        Fake.bad_url = True
        out = self.run_main(["--i-own-this-store"])
        self.assertIn("EXIT", out)
        self.assertIn(("POST", "cancel_checkout"), Fake.calls)

    def test_a_failed_create_leaves_nothing_to_cancel(self):
        Fake.fail_create = True
        self.run_main(["--i-own-this-store"])
        self.assertNotIn(("POST", "cancel_checkout"), Fake.calls)


if __name__ == "__main__":
    unittest.main()
