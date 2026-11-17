"""Tests of shopify_live.py's probe checkout against a fake store (never Shopify): the probe is canceled on every way out
of it, or, when it cannot be, the script says so. Run by hand: python3 -I scripts/shopify_live_test.py
Not part of ./mvnw verify (verify needs no Python)."""
import io
import json
import os
import shutil
import signal
import sqlite3
import subprocess
import sys
import tempfile
import textwrap
import unittest
from contextlib import redirect_stdout

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import shopify_live as live  # noqa: E402

LINES = [{"item": {"id": "gid://shopify/ProductVariant/1"}, "quantity": 1}]
PROBE = "gid://shopify/Checkout/probe1?key=fake"


class FakeStore(live.Store):
    """Answers each tool from `answers` (a value, or an exception to raise) and records every tool name it was asked."""

    def __init__(self, answers):
        super().__init__("my-dev-store.myshopify.com", False)
        self.answers = answers
        self.calls = []

    def tool(self, step, name, arguments, record_as, mutating=False):
        self.calls.append(name)
        answer = self.answers.get(name, {})
        if isinstance(answer, BaseException):
            raise answer
        return answer


def created(**extra):
    return dict({"id": PROBE, "status": "incomplete", "currency": "USD", "totals": []}, **extra)


def session(store, sleep=lambda s: None):
    out = io.StringIO()
    try:
        with redirect_stdout(out):
            live.probe_session(store, LINES, lambda: None, sleep)
    except BaseException as e:      # what got out is asserted on, not swallowed
        return out.getvalue(), e
    return out.getvalue(), None


class ProbeCleanup(unittest.TestCase):

    def test_theHappyPath_cancelsOnce(self):
        store = FakeStore({"create_checkout": created()})
        _, raised = session(store)
        self.assertIsNone(raised)
        self.assertEqual(store.calls.count("cancel_checkout"), 1)

    def test_anErrorAfterTheCreate_cancels(self):
        store = FakeStore({"create_checkout": created(), "get_checkout": RuntimeError("get_checkout answered HTTP 500")})
        _, raised = session(store)
        self.assertIsInstance(raised, RuntimeError)
        self.assertEqual(store.calls[-1], "cancel_checkout")

    def test_aTimeoutAfterTheCreate_cancels(self):
        store = FakeStore({"create_checkout": created(), "get_checkout": TimeoutError("timed out")})
        _, raised = session(store)
        self.assertIsInstance(raised, TimeoutError)
        self.assertEqual(store.calls[-1], "cancel_checkout")

    def test_ctrlC_duringTheGap_cancels(self):
        store = FakeStore({"create_checkout": created()})

        def interrupted(seconds):
            raise KeyboardInterrupt()
        _, raised = session(store, interrupted)
        self.assertIsInstance(raised, KeyboardInterrupt)
        self.assertEqual(store.calls[-1], "cancel_checkout")

    def test_aFailedCancel_isRetriedOnTheWayOut_andALeftOpenProbeIsLoud(self):
        store = FakeStore({"create_checkout": created(), "cancel_checkout": RuntimeError("cancel answered HTTP 503")})
        out, raised = session(store)
        self.assertIsInstance(raised, RuntimeError)
        self.assertEqual(store.calls.count("cancel_checkout"), 2)
        self.assertIn("WARNING: the probe checkout may be left open", out)

    def test_ctrlC_duringTheCancelOnTheWayOut_doesNotCutTheCleanupShort(self):
        store = FakeStore({"create_checkout": created(), "get_checkout": RuntimeError("get answered HTTP 500"),
                           "cancel_checkout": KeyboardInterrupt()})
        out, raised = session(store)
        self.assertIsInstance(raised, RuntimeError, "the first failure is what gets out, not the interrupt")
        self.assertIn("WARNING: the probe checkout may be left open", out)

    def test_ctrlC_whileTheCreateIsInFlight_isLoud(self):
        store = FakeStore({"create_checkout": KeyboardInterrupt()})
        out, raised = session(store)
        self.assertIsInstance(raised, KeyboardInterrupt)
        self.assertIn("WARNING: a probe checkout may be open", out)

    def test_aCreateNamingNoCheckout_isLoud(self):
        store = FakeStore({"create_checkout": {"status": "incomplete"}})
        out, _ = session(store)
        self.assertIn("WARNING: a probe checkout may be open", out)

    def test_sigterm_duringTheGap_cancels(self):
        with tempfile.TemporaryDirectory() as work:
            calls = os.path.join(work, "calls")
            ready = os.path.join(work, "ready")
            driver = textwrap.dedent("""
                import json, os, sys, time
                sys.path.insert(0, %r)
                import shopify_live as live
                class Store(live.Store):
                    def tool(self, step, name, arguments, record_as, mutating=False):
                        with open(%r, "a") as f:
                            f.write(name + "\\n")
                        return {"id": %r, "totals": []} if name == "create_checkout" else {}
                def gap(seconds):
                    open(%r, "w").close()
                    time.sleep(30)
                try:
                    live.probe_session(Store("my-dev-store.myshopify.com", False), [], lambda: None, gap)
                except BaseException:
                    pass
            """ % (HERE, calls, PROBE, ready))
            p = subprocess.Popen([sys.executable, "-I", "-c", driver], stdout=subprocess.DEVNULL)
            for _ in range(100):
                if os.path.exists(ready):
                    break
                p.wait(timeout=0.1) if p.poll() is not None else __import__("time").sleep(0.1)
            p.send_signal(signal.SIGTERM)
            p.wait(timeout=20)
            with open(calls) as f:
                names = f.read().split()
            self.assertEqual(names[-1], "cancel_checkout", "SIGTERM during the gap left the probe open: %s" % names)



class FakeApi:
    """The owner's API for one intent that ended without an approval."""

    def __init__(self, intent, events):
        self.answers = {"/api/intents/" + intent: {"id": intent, "state": "FAILED_NOT_CHARGED",
                                                   "failure_reason": "quote_retries_exhausted"},
                        "/api/intents/%s/events" % intent: {"items": events}}

    def json(self, method, path, body=None):
        return self.answers[path]


def attempts_db(path, intent, rows):
    with sqlite3.connect(path) as c:
        c.execute("CREATE TABLE purchase_intent (id TEXT PRIMARY KEY, session_id TEXT, requote_count INTEGER)")
        c.execute("CREATE TABLE merchant_attempt (id TEXT PRIMARY KEY, intent_id TEXT, seq INTEGER, op TEXT, revision "
                  "INTEGER, idempotency_key TEXT, request_hash TEXT, request_body TEXT, status TEXT, http_status "
                  "INTEGER, response_json TEXT, sent_at TEXT, responded_at TEXT)")
        c.execute("INSERT INTO purchase_intent VALUES (?, NULL, 0)", (intent,))
        for i, (op, key, body, status, response) in enumerate(rows):
            c.execute("INSERT INTO merchant_attempt VALUES (?, ?, ?, ?, 0, ?, 'h', ?, ?, NULL, ?, 't0', 't1')",
                      ("a%d" % i, intent, i + 1, op, key, body, status, response))


class FailedQuoteEvidence(unittest.TestCase):
    """A quote that opens no approval keeps the intent, its events and its attempts, and replays the failing create
    with its own key so the store's raw answer is recorded, redacted; a checkout the replay names is canceled."""

    INTENT = "01a1ea58-0000-7000-8000-000000000001"
    KEY = "6f1c2a3b-0000-8000-8000-000000000002"
    BODY = json.dumps({"buyer": {"email": "alice@local.ucplab.dev"},
                       "line_items": [{"item": {"id": "gid://shopify/ProductVariant/1"}, "quantity": 1}]})

    def setUp(self):
        self.work = tempfile.mkdtemp()
        self.db = os.path.join(self.work, "ucplab.db")
        attempts_db(self.db, self.INTENT, [("CREATE", self.KEY, self.BODY, "UNCERTAIN",
                                             json.dumps({"outcome": "uncertain", "cause": "an answer the parser refused"}))])
        live.remember("canary-cookie-0042")
        live.spike.RECORD[0] = os.path.join(self.work, "recordings")
        os.makedirs(live.spike.RECORD[0])

    def tearDown(self):
        live.spike.RECORD[0] = None
        shutil.rmtree(self.work)

    def test_aFailedQuote_keepsTheIntentItsEventsAndItsAttempts_redacted(self):
        evidence = os.path.join(self.work, "evidence")
        os.makedirs(evidence)
        api = FakeApi(self.INTENT, [{"type": "transition", "payload": "canary-cookie-0042"}])
        with redirect_stdout(io.StringIO()):
            live.save_intent_evidence(api, self.db, self.INTENT, evidence)
        names = sorted(os.listdir(evidence))
        self.assertEqual(names, ["attempts.json", "events.json", "intent.json"])
        attempts = json.load(open(os.path.join(evidence, "attempts.json")))
        self.assertEqual([(a["op"], a["status"]) for a in attempts], [("CREATE", "UNCERTAIN")])
        self.assertIn("uncertain", json.dumps(attempts))
        for name in names:
            self.assertNotIn("canary-cookie-0042", open(os.path.join(evidence, name)).read())

    def test_theFailingCreate_isReplayedWithItsOwnKey_recorded_andItsCheckoutCanceled(self):
        sent = []

        class Store(FakeStore):
            def tool(self, step, name, arguments, record_as, mutating=False, key=None):
                sent.append((name, arguments, key, record_as))
                return {"id": PROBE, "status": "incomplete", "totals": [],
                        "messages": [{"code": "x", "severity": "unrecoverable"}]} if name == "create_checkout" else {}
        store = Store({})
        store.endpoint = "https://my-dev-store.myshopify.com/api/ucp/mcp"
        with redirect_stdout(io.StringIO()):
            live.replay_failed_quote(store, self.db, self.INTENT)
        self.assertEqual([s[0] for s in sent], ["create_checkout", "cancel_checkout"])
        name, arguments, key, record_as = sent[0]
        self.assertEqual(key, self.KEY, "the replay uses the agent's own idempotency key")
        self.assertEqual(arguments, {"checkout": json.loads(self.BODY)})
        self.assertEqual(record_as, "agent_create_replay")
        self.assertEqual(sent[1][1], {"id": PROBE})


if __name__ == "__main__":
    unittest.main()
