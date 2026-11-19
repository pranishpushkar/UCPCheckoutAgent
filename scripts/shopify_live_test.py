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
import time
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

    def test_theHappyPath_isNotLoud(self):
        # Stage 3: after "probe canceled (status: canceled)" the script also warned that a probe may be open with no id
        store = FakeStore({"create_checkout": created(), "cancel_checkout": {"status": "canceled"}})
        out, raised = session(store)
        self.assertIsNone(raised)
        self.assertIn("probe canceled (status: canceled)", out)
        self.assertNotIn("WARNING", out)

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



class HandedOffEvidence(unittest.TestCase):
    """A handed-off intent's evidence keeps where the buyer was sent (the host) but never the handoff URL itself: its
    cart token and key are the buyer's way into the checkout (I8; docs/interop/shopify-live.md, What is never written)."""

    INTENT = "01a1eabe-0000-7000-8000-000000000001"
    URL = "https://my-dev-store.myshopify.com/cart/c/tok123cart?key=canarykey777"

    def setUp(self):
        self.work = tempfile.mkdtemp()
        self.db = os.path.join(self.work, "ucplab.db")
        attempts_db(self.db, self.INTENT, [])

    def tearDown(self):
        shutil.rmtree(self.work)

    def test_theHandoffUrl_isCutToItsHost(self):
        api = FakeApi(self.INTENT, [])
        api.answers["/api/intents/" + self.INTENT] = {"id": self.INTENT, "state": "HANDED_OFF", "handoff_url": self.URL}
        with redirect_stdout(io.StringIO()):
            live.save_intent_evidence(api, self.db, self.INTENT, self.work)
        intent = open(os.path.join(self.work, "intent.json")).read()
        self.assertNotIn("tok123cart", intent)
        self.assertNotIn("/cart/c/", intent)
        self.assertNotIn("canarykey777", intent)
        self.assertEqual(json.loads(intent)["handoff_url"], "https://my-dev-store.myshopify.com/...")

class OwedCancels(unittest.TestCase):
    """Before the service stops, the script waits, bounded, for the run's owed cancels (7.6) to drain; what is still
    owed at the bound, or was given up, is named so the owner can cancel it in the store admin, the key masked."""

    OWED = "01a1ea58-0000-7000-8000-00000000000a"
    ORPHAN = "01a1ea58-0000-7000-8000-00000000000b"
    SESSION = "gid://shopify/Checkout/tok123?key=canarykey999"

    def setUp(self):
        self.work = tempfile.mkdtemp()
        self.db = os.path.join(self.work, "ucplab.db")
        with sqlite3.connect(self.db) as c:
            c.execute("CREATE TABLE purchase_intent (id TEXT PRIMARY KEY, session_id TEXT, cancel_pending INTEGER, "
                      "created_at TEXT)")
            c.execute("CREATE TABLE intent_event (intent_id TEXT, seq INTEGER, type TEXT, payload_json TEXT, at TEXT)")
            c.execute("INSERT INTO purchase_intent VALUES (?, ?, 1, 't0')", (self.OWED, self.SESSION))
            c.execute("INSERT INTO purchase_intent VALUES (?, NULL, 1, 't0')", (self.ORPHAN,))

    def tearDown(self):
        shutil.rmtree(self.work)

    def clear(self, intent):
        with sqlite3.connect(self.db) as c:
            c.execute("UPDATE purchase_intent SET cancel_pending = 0 WHERE id = ?", (intent,))

    def drain(self, sleep, clock):
        out = io.StringIO()
        with redirect_stdout(out):
            left = live.drain_cancels(self.db, [self.OWED, self.ORPHAN], 30, sleep, clock)
        return out.getvalue(), left

    def test_owedCancels_thatDrain_areWaitedFor_andNothingIsLeft(self):
        naps = []

        def worker_pass(seconds):      # the service's recovery worker sends both cancels after two naps
            naps.append(seconds)
            if len(naps) == 2:
                self.clear(self.OWED)
                self.clear(self.ORPHAN)
        out, left = self.drain(worker_pass, lambda: 0.0)
        self.assertEqual(len(naps), 2, "the script waited for the worker")
        self.assertEqual(left, [])
        self.assertNotIn("WARNING", out)

    def test_atTheBound_theCheckoutsStillOwed_areNamed_withTheirKeyMasked(self):
        now = [0.0]

        def nap(seconds):
            now[0] += seconds
        out, left = self.drain(nap, lambda: now[0])
        self.assertGreaterEqual(now[0], 30, "it waited the whole bound")
        self.assertLess(now[0], 32, "and no longer")
        self.assertEqual(sorted(i for i, _ in left), sorted([self.OWED, self.ORPHAN]))
        self.assertIn("gid://shopify/Checkout/tok123", out)
        self.assertNotIn("canarykey999", out, "the checkout's key is never printed")
        self.assertIn(self.ORPHAN, out)
        self.assertIn("no checkout id", out)

    def test_aCancelTheWorkerGaveUpOn_isNamed_too(self):
        self.clear(self.OWED)
        self.clear(self.ORPHAN)
        with sqlite3.connect(self.db) as c:
            c.execute("INSERT INTO intent_event VALUES (?, 1, 'cancel_gave_up', '{}', 't1')", (self.OWED,))
        out, left = self.drain(lambda s: None, lambda: 0.0)
        self.assertEqual(left, [])
        self.assertIn("gave up", out)
        self.assertIn("gid://shopify/Checkout/tok123", out)
        self.assertNotIn("canarykey999", out)



class Console(unittest.TestCase):
    """--console (scripts/shopify-console.sh): the service and its owned store, left running for the owner's browser.
    The Stage 3 guards stay; approval is the console's card only; on exit every owed cancel is drained, and a handed-off
    checkout is left open, since the owner may be paying at the store."""

    HANDED = "01a1ea58-0000-7000-8000-0000000000c1"
    OWED = "01a1ea58-0000-7000-8000-0000000000c2"
    SESSION = "gid://shopify/Checkout/tok777?key=canarykey777"

    def setUp(self):
        self.work = tempfile.mkdtemp()
        self.db = os.path.join(self.work, "ucplab.db")
        with sqlite3.connect(self.db) as c:
            c.execute("CREATE TABLE purchase_intent (id TEXT PRIMARY KEY, session_id TEXT, cancel_pending INTEGER, "
                      "created_at TEXT, requote_count INTEGER)")
            c.execute("CREATE TABLE intent_event (intent_id TEXT, seq INTEGER, type TEXT, payload_json TEXT, at TEXT)")
            c.execute("CREATE TABLE merchant_attempt (id TEXT PRIMARY KEY, intent_id TEXT, seq INTEGER, op TEXT, revision "
                      "INTEGER, idempotency_key TEXT, request_hash TEXT, request_body TEXT, status TEXT, http_status "
                      "INTEGER, response_json TEXT, sent_at TEXT, responded_at TEXT)")
            c.execute("INSERT INTO purchase_intent VALUES (?, ?, 0, 't0', 0)", (self.HANDED, self.SESSION))
            c.execute("INSERT INTO purchase_intent VALUES (?, NULL, 1, 't1', 0)", (self.OWED,))

    def tearDown(self):
        shutil.rmtree(self.work)

    def test_theScriptedRunsOptions_areRefused(self):
        for flag in ("--approve", "--cancel-after", "--query", "--record"):
            with self.assertRaises(SystemExit, msg=flag):
                live.console_options(["--console", flag])
        live.console_options(["--console", "--i-own-this-store", "--with-buyer-ip", "--port", "8080"])

    def test_theStage3Guards_stay(self):
        for missing in ("--i-own-this-store", "--with-buyer-ip"):
            argv = [a for a in ("--console", "--dry-run", "--i-own-this-store", "--with-buyer-ip") if a != missing]
            with self.assertRaises(SystemExit) as e:
                live.main(argv)
            self.assertIn("refused", str(e.exception))

    def test_theServiceListensOnLoopbackOnly(self):
        env = live.service_env({"PATH": "/bin"}, self.work, 8080)
        self.assertEqual(env.get("SERVER_ADDRESS"), "127.0.0.1")

    def test_theDryRun_callsNothing_approvesOnTheCardOnly_andLeavesAHandedOffCheckoutOpen(self):
        out = io.StringIO()
        with redirect_stdout(out):
            code = live.main(["--console", "--dry-run", "--i-own-this-store", "--with-buyer-ip"])
        text = out.getvalue()
        self.assertEqual(code, 0)
        self.assertIn("127.0.0.1", text)
        self.assertIn("sign in as alice", text)
        self.assertIn("approve on the console's card", text)
        self.assertIn("Ctrl-C", text)
        self.assertIn("owed cancels", text)
        self.assertIn("a HANDED_OFF checkout is not cancelled", text)
        for never in ("cancel_checkout", "create_checkout", "get_checkout", "POST /api/runs", "Approve this card"):
            self.assertNotIn(never, text)

    def test_theIntents_areEveryOneTheConsoleMade_inOrder(self):
        self.assertEqual(live.console_intents(self.db), [self.HANDED, self.OWED])

    def test_onExit_everyOwedCancelIsDrained_theEvidenceKept_andAHandedOffCheckoutLeftOpen(self):
        evidence = os.path.join(self.work, "evidence")
        os.makedirs(evidence)
        drained = []

        class Api:
            def json(self, method, path, body=None):
                if path.endswith("/events"):
                    return {"items": []}
                intent = path.rsplit("/", 1)[1]
                state = "HANDED_OFF" if intent == Console.HANDED else "FAILED_NOT_CHARGED"
                return {"id": intent, "state": state, "handoff_url": "https://shop.example/cart/c/tok777?key=canarykey777"}

        out = io.StringIO()
        with redirect_stdout(out):
            live.console_exit(Api(), self.db, evidence, lambda db, intents: drained.append(list(intents)) or [])
        self.assertEqual(drained, [[self.HANDED, self.OWED]])
        for intent in (self.HANDED, self.OWED):
            self.assertTrue(os.path.isfile(os.path.join(evidence, intent, "intent.json")), intent)
        saved = open(os.path.join(evidence, self.HANDED, "intent.json")).read()
        self.assertNotIn("canarykey777", saved)
        self.assertNotIn("/cart/c/", saved)
        self.assertIn("left open", out.getvalue())
        self.assertNotIn("canarykey777", out.getvalue())

    # 7.6 handoff follow-up, VERIFY 11, I8: on exit a handed-off intent whose follow-up saw the store's order says so with
    # the order id; its confirmation code (store_order.label) is kept out of the terminal and out of the saved evidence.
    def test_onExit_aSeenStoreOrder_isNamedByItsId_neverItsLabel(self):
        evidence = os.path.join(self.work, "evidence")
        os.makedirs(evidence)

        class Api:
            def json(self, method, path, body=None):
                if path.endswith("/events"):
                    return {"items": []}
                intent = path.rsplit("/", 1)[1]
                if intent != Console.HANDED:
                    return {"id": intent, "state": "FAILED_NOT_CHARGED"}
                return {"id": intent, "state": "HANDED_OFF", "follow_up": {"watching": False},
                        "store_order": {"id": "gid://shopify/Order/42", "label": "CANARYLABEL7"}}

        out = io.StringIO()
        with redirect_stdout(out):
            live.console_exit(Api(), self.db, evidence, lambda db, intents: [])
        self.assertIn("the store reported order gid://shopify/Order/42", out.getvalue())
        self.assertNotIn("CANARYLABEL7", out.getvalue())
        self.assertNotIn("CANARYLABEL7", open(os.path.join(evidence, self.HANDED, "intent.json")).read())

    def test_serving_endsOnCtrlC_orWhenTheServiceStops(self):
        class Proc:
            def __init__(self, code):
                self.code = code

            def poll(self):
                return self.code

        def ctrl_c(seconds):
            raise KeyboardInterrupt()
        out = io.StringIO()
        with redirect_stdout(out):
            live.serve_console(Proc(None), "http://127.0.0.1:8080", ctrl_c)
            live.serve_console(Proc(1), "http://127.0.0.1:8080", ctrl_c)
        text = out.getvalue()
        self.assertIn("http://127.0.0.1:8080", text)
        self.assertIn("stopping", text)
        self.assertIn("the service stopped", text)



class ReadStore(live.Store):
    """A fake store for the after-payment reads: answers get_checkout from `answers` (a value, or an exception), records
    each answer through spike.record as spike.call does, and counts open() and every call."""

    def __init__(self, answers):
        super().__init__("my-dev-store.myshopify.com", False)
        self.answers = answers
        self.opened = 0
        self.calls = []

    def open(self):
        self.opened += 1

    def tool(self, step, name, arguments, record_as, mutating=False, key=None):
        self.calls.append((name, arguments.get("id"), record_as, mutating))
        answer = self.answers.get(arguments.get("id"), {})
        if isinstance(answer, list):
            answer = answer.pop(0)
        if isinstance(answer, BaseException):
            raise answer
        live.spike.record(record_as, answer)
        return answer


class AfterPayment(unittest.TestCase):
    """--after-payment (part 1 of notes/h5/after-handoff-order-proposal.md, VERIFY 7): on the console's way out, one
    read-only get_checkout per HANDED_OFF checkout, recorded redacted under evidence/<intent>/, then one more 60 s later.
    Nothing is sent to the store without the flag."""

    HANDED = Console.HANDED
    OWED = Console.OWED
    SESSION = Console.SESSION
    HANDED_2 = "01a1ea58-0000-7000-8000-0000000000c3"
    SESSION_2 = "gid://shopify/Checkout/tok888?key=canarykey888"

    def setUp(self):
        Console.setUp(self)
        with sqlite3.connect(self.db) as c:
            c.execute("INSERT INTO purchase_intent VALUES (?, ?, 0, 't2', 0)", (self.HANDED_2, self.SESSION_2))
        self.evidence = os.path.join(self.work, "evidence")
        os.makedirs(self.evidence)
        self.naps = []

    def tearDown(self):
        live.spike.RECORD[0] = None
        shutil.rmtree(self.work)

    class Api:
        def json(self, method, path, body=None):
            if path.endswith("/events"):
                return {"items": []}
            intent = path.rsplit("/", 1)[1]
            state = "FAILED_NOT_CHARGED" if intent == AfterPayment.OWED else "HANDED_OFF"
            return {"id": intent, "state": state}

    def paid(self, session):
        return {"id": session, "status": "completed", "currency": "USD",
                "continue_url": "https://shop.example/cart/c/tok777?key=canarykey777",
                "order": {"id": "gid://shopify/Order/42", "permalink_url": "https://shop.example/orders/abc?key=canarykey777"}}

    def reads(self, store):
        out = io.StringIO()
        with redirect_stdout(out):
            names = live.after_payment_reads(store, self.db, self.Api(), self.evidence, self.naps.append, 60)
        return out.getvalue(), names

    def test_withoutTheFlag_nothingIsRead(self):
        store = ReadStore({})
        self.assertIsNone(live.after_payment_for(["--console"], lambda: store, self.db, self.Api(), self.evidence))
        self.assertIsNotNone(live.after_payment_for(["--console", "--after-payment"], lambda: store, self.db, self.Api(),
                                                    self.evidence))
        self.assertEqual(store.calls, [])
        self.assertEqual(store.opened, 0)

    def test_eachHandedOffCheckout_isReadTwice_60sApart_readOnly_andRecordedRedacted(self):
        store = ReadStore({self.SESSION: [{"id": self.SESSION, "status": "incomplete"}, self.paid(self.SESSION)],
                           self.SESSION_2: [{"id": self.SESSION_2, "status": "incomplete"}] * 2})
        out, names = self.reads(store)

        self.assertEqual(store.opened, 1)
        self.assertEqual(self.naps, [60], "one wait of 60 s between the two rounds")
        self.assertEqual([(n, i, m) for n, i, _, m in store.calls], [
            ("get_checkout", self.SESSION, False), ("get_checkout", self.SESSION_2, False),
            ("get_checkout", self.SESSION, False), ("get_checkout", self.SESSION_2, False)])
        for intent in (self.HANDED, self.HANDED_2):
            for name in ("after_payment_get_checkout_0s", "after_payment_get_checkout_60s"):
                self.assertTrue(os.path.isfile(os.path.join(self.evidence, intent, name + ".json")), (intent, name))
        self.assertFalse(os.path.exists(os.path.join(self.evidence, self.OWED, "after_payment_get_checkout_0s.json")))
        saved = open(os.path.join(self.evidence, self.HANDED, "after_payment_get_checkout_60s.json")).read()
        self.assertIn("completed", saved)
        self.assertIn("gid://shopify/Order/42", saved)
        self.assertNotIn("canarykey777", saved)
        self.assertNotIn("/cart/c/", saved)
        self.assertIn("completed", out)
        self.assertNotIn("canarykey", out)
        self.assertEqual(len(names), 4)

    def test_withNoHandedOffCheckout_nothingIsSent(self):
        with sqlite3.connect(self.db) as c:
            c.execute("DELETE FROM purchase_intent WHERE id != ?", (self.OWED,))
        store = ReadStore({})
        out, names = self.reads(store)
        self.assertEqual((store.opened, store.calls, self.naps, names), (0, [], [], []))
        self.assertIn("no HANDED_OFF checkout", out)

    def test_aReadThatFails_isNamed_andTheRestStillRun(self):
        store = ReadStore({self.SESSION: [RuntimeError("the store did not answer"), self.paid(self.SESSION)],
                           self.SESSION_2: [{"id": self.SESSION_2}] * 2})
        out, names = self.reads(store)
        self.assertEqual(len(store.calls), 4)
        self.assertIn("WARNING", out)
        self.assertEqual(len(names), 3)

    def test_onExit_theReadsComeAfterTheEvidence_andBeforeTheDrain(self):
        order = []
        with redirect_stdout(io.StringIO()):
            live.console_exit(self.Api(), self.db, self.evidence,
                              lambda db, intents: order.append("drain") or [], lambda: order.append("reads"))
        self.assertEqual(order, ["reads", "drain"])
        self.assertTrue(os.path.isfile(os.path.join(self.evidence, self.HANDED, "intent.json")))

    def test_theFlag_isForTheConsoleOnly(self):
        with self.assertRaises(SystemExit) as e:
            live.main(["--after-payment", "--dry-run", "--i-own-this-store", "--with-buyer-ip"])
        self.assertIn("refused", str(e.exception))

    def test_theDryRun_showsTheTwoReads_withTheSecretsReplaced(self):
        out = io.StringIO()
        with redirect_stdout(out):
            code = live.main(["--console", "--after-payment", "--dry-run", "--i-own-this-store", "--with-buyer-ip"])
        text = out.getvalue()
        self.assertEqual(code, 0)
        self.assertEqual(text.count('"name": "get_checkout"'), 2, "exactly two calls")
        self.assertEqual(text.count('"method": "tools/call"'), 2, "and no other tool call")
        self.assertIn("wait 60 s", text)
        self.assertIn('"Authorization": "<redacted>"', text)
        self.assertIn('"Shopify-Buyer-IP": "<redacted>"', text)
        for never in ("cancel_checkout", "create_checkout", "update_checkout", "complete_checkout"):
            self.assertNotIn(never, text)



# "The script" for CtrlC: spawns a sleeper as the service is spawned, says the sleeper's pid, and waits for Ctrl-C. On
# it, it says whether the sleeper is still alive, then stops it with stop_service and says how it ended.
HARNESS = textwrap.dedent("""
    import os, subprocess, sys, tempfile, time
    sys.path.insert(0, sys.argv[1])
    import shopify_live as live
    tmp = tempfile.mkdtemp()
    child = live.spawn([sys.executable, "-I", "-c", "import time; time.sleep(60)"], {"PATH": os.environ.get("PATH", "")},
                       subprocess.DEVNULL, tmp)
    print("child", child.pid, flush=True)
    try:
        time.sleep(30)
        print("no ctrl-c", flush=True)
    except KeyboardInterrupt:
        time.sleep(1)                       # a child that got the SIGINT too has died by now
        print("after ctrl-c, child alive:", child.poll() is None, flush=True)
        live.stop_service(child, None, tmp)
        print("after stop_service, child exit:", child.poll(), flush=True)
""")


class CtrlC(unittest.TestCase):
    """Ctrl-C in the terminal reaches the script alone: the service runs in its own session, so its cleanup (the
    evidence, the after-payment reads, the owed-cancel drain) can still talk to it; the script stops it after."""

    def test_ctrlC_toTheScriptsProcessGroup_leavesTheServiceRunning_untilStopService(self):
        harness = subprocess.Popen([sys.executable, "-I", "-c", HARNESS, HERE], stdout=subprocess.PIPE, text=True,
                                   start_new_session=True)      # its own group, so the SIGINT never reaches this runner
        child = None
        try:
            first = harness.stdout.readline().split()
            self.assertEqual(first[0], "child", first)
            child = int(first[1])
            time.sleep(0.5)
            os.killpg(harness.pid, signal.SIGINT)              # what the terminal does on Ctrl-C
            out, _ = harness.communicate(timeout=60)
        finally:
            if harness.poll() is None:
                harness.kill()
            if child:
                try:
                    os.kill(child, signal.SIGKILL)
                except ProcessLookupError:
                    pass
        self.assertIn("after ctrl-c, child alive: True", out)
        self.assertRegex(out, r"after stop_service, child exit: -?\d+")

    def test_bothRuns_startTheServiceInItsOwnSession(self):
        seen = []

        class Popen:
            def __init__(self, command, **kwargs):
                seen.append(kwargs)

        work = tempfile.mkdtemp()
        real = live.subprocess.Popen
        try:
            live.subprocess.Popen = Popen
            proc, log = live.start_service("agent-service.jar", work, "my-dev-store.myshopify.com", work, 8080)
            log.close()
        finally:
            live.subprocess.Popen = real
            shutil.rmtree(work)
        self.assertEqual(len(seen), 1)
        self.assertIs(seen[0].get("start_new_session"), True)



class GeminiModel(unittest.TestCase):
    """--model gemini (DESIGN 26.6), with --console only: the service runs AGENT_MODEL=gemini with GEMINI_API_KEY from
    the owner's environment only. Refused in one line when the key is unset; the key is never an argument, never printed
    and never in the evidence. Without the option the run stays scripted, whatever the environment holds."""

    KEY = "AIzaCANARY" + "k" * 10 + "0123456789abcde"
    ENV = {"SHOPIFY_CLIENT_ID": "cid-canary-1", "SHOPIFY_CLIENT_SECRET": "csec-canary-1",
           "SHOPIFY_STORE_DOMAIN": "my-dev-store.myshopify.com", "AGENT_PROFILE_URL": "https://example.test/profile.json",
           "BUYER_IP": "81.2.69.161", "PATH": os.environ.get("PATH", "/bin")}
    GUARDS = ["--console", "--i-own-this-store", "--with-buyer-ip"]

    def setUp(self):
        self.saved = dict(os.environ)
        self.work = tempfile.mkdtemp()
        os.environ.clear()
        os.environ.update(self.ENV)

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self.saved)
        if self.KEY in live.spike.TOKEN:
            live.spike.TOKEN.remove(self.KEY)
        shutil.rmtree(self.work)

    def assertTakesAModel(self, function):
        import inspect
        self.assertIn("model", inspect.signature(function).parameters, "%s takes the model" % function.__name__)

    def dry_run(self, *extra):
        out = io.StringIO()
        with redirect_stdout(out):
            code = live.main(self.GUARDS + ["--dry-run"] + list(extra))
        self.assertEqual(code, 0)
        return out.getvalue()

    # Covers: 26.6 (scripted stays the default: no --model, no key passed on even when the shell holds one)
    def test_scripted_staysTheDefault_andNoKeyIsPassedOn(self):
        os.environ["GEMINI_API_KEY"] = self.KEY
        env = live.service_env(os.environ, self.work, 8080)
        self.assertEqual(env["AGENT_MODEL"], "scripted")
        self.assertNotIn("GEMINI_API_KEY", env)
        self.assertNotIn(self.KEY, json.dumps(env))
        text = self.dry_run()
        self.assertIn('"AGENT_MODEL": "scripted"', text)
        self.assertNotIn("GEMINI_API_KEY", text)
        self.assertNotIn(self.KEY, text)

    # Covers: 26.6, I8 (--model gemini without the key refuses to start, in one line that names the variable; a blank
    # key is unset)
    def test_gemini_withoutTheKey_isRefusedInOneLine(self):
        for value in (None, "", "   "):
            if value is None:
                os.environ.pop("GEMINI_API_KEY", None)
            else:
                os.environ["GEMINI_API_KEY"] = value
            with self.assertRaises(SystemExit) as e:
                live.main(self.GUARDS + ["--model", "gemini"])
            message = str(e.exception)
            self.assertEqual(message, "refused: --model gemini needs GEMINI_API_KEY in your environment "
                                      "(it is never taken as an argument)", repr(value))
            self.assertNotIn("\n", message)

    # Covers: 26.6 (an unknown model, and --model outside --console, are refused)
    def test_anUnknownModel_andTheOptionOutsideTheConsole_areRefused(self):
        os.environ["GEMINI_API_KEY"] = self.KEY
        with self.assertRaises(SystemExit) as e:
            live.main(self.GUARDS + ["--dry-run", "--model", "gpt"])
        self.assertEqual(str(e.exception), "refused: --model must be scripted or gemini")
        with self.assertRaises(SystemExit) as e:
            live.main(["--dry-run", "--i-own-this-store", "--with-buyer-ip", "--model", "gemini"])
        self.assertEqual(str(e.exception), "refused: --model belongs to --console (scripts/shopify-console.sh)")

    # Covers: 26.6, I8 (with --model gemini the service gets AGENT_MODEL=gemini and the key in its environment only;
    # the dry run shows the key as <redacted> and never its value)
    def test_gemini_putsTheKeyInTheServicesEnvironmentOnly_andTheDryRunRedactsIt(self):
        os.environ["GEMINI_API_KEY"] = self.KEY
        self.assertTakesAModel(live.service_env)
        env = live.service_env(os.environ, self.work, 8080, model="gemini")
        self.assertEqual(env["AGENT_MODEL"], "gemini")
        self.assertEqual(env["GEMINI_API_KEY"], self.KEY)
        text = self.dry_run("--model", "gemini")
        self.assertIn('"AGENT_MODEL": "gemini"', text)
        self.assertIn('"GEMINI_API_KEY": "<redacted>"', text)
        self.assertNotIn(self.KEY, text)

    # Covers: 26.6, I8 (the key is never an argument of the service's command, and is redacted from every printed line
    # and every evidence file from the moment the service's environment holds it)
    def test_theKey_isNeverAnArgument_andIsRedactedFromTheEvidence(self):
        os.environ["GEMINI_API_KEY"] = self.KEY
        self.assertTakesAModel(live.start_service)
        seen = []

        class Popen:
            def __init__(self, command, **kwargs):
                seen.append((command, kwargs))

        real = live.subprocess.Popen
        try:
            live.subprocess.Popen = Popen
            proc, log = live.start_service("agent-service.jar", self.work, "my-dev-store.myshopify.com", self.work,
                                           8080, model="gemini")
            log.close()
        finally:
            live.subprocess.Popen = real
        command, kwargs = seen[0]
        self.assertNotIn(self.KEY, " ".join(command))
        self.assertEqual(kwargs["env"]["GEMINI_API_KEY"], self.KEY)
        self.assertEqual(live.spike.redact("model key " + self.KEY), "model key <redacted>")
        for name in os.listdir(self.work):
            with open(os.path.join(self.work, name), errors="replace") as f:
                self.assertNotIn(self.KEY, f.read(), name)
        out = io.StringIO()
        with redirect_stdout(out):
            live.say("the key is " + self.KEY)
        self.assertNotIn(self.KEY, out.getvalue())

    # Covers: 26.6 (the jar the console runs must carry agent-adk, the live client, on its runtime classpath; one
    # without it is refused before anything starts, saying what is needed)
    def test_theJar_mustCarryAgentAdk(self):
        import zipfile
        self.assertTrue(hasattr(live, "jar_has_live_model"), "the script checks the jar for agent-adk")
        good = os.path.join(self.work, "good.jar")
        bad = os.path.join(self.work, "bad.jar")
        with zipfile.ZipFile(good, "w") as z:
            z.writestr("BOOT-INF/classpath.idx", '- "BOOT-INF/lib/agent-core-0.1.0-SNAPSHOT.jar"\n'
                                                 '- "BOOT-INF/lib/agent-adk-0.1.0-SNAPSHOT.jar"\n')
        with zipfile.ZipFile(bad, "w") as z:
            z.writestr("BOOT-INF/classpath.idx", '- "BOOT-INF/lib/agent-core-0.1.0-SNAPSHOT.jar"\n')
        self.assertTrue(live.jar_has_live_model(good))
        self.assertFalse(live.jar_has_live_model(bad))
        self.assertFalse(live.jar_has_live_model(os.path.join(self.work, "missing.jar")))
        os.environ["GEMINI_API_KEY"] = self.KEY
        with self.assertRaises(SystemExit) as e:
            live.console_main(self.GUARDS + ["--model", "gemini", "--evidence", self.work], "my-dev-store.myshopify.com",
                              self.work, 8080, bad)
        self.assertEqual(str(e.exception), "refused: the jar has no agent-adk on its runtime classpath, so it cannot run "
                                           "Gemini; rebuild it: ./mvnw -B -ntp -DskipTests package")

    # Covers: 26.6 (the jar this repository builds carries agent-adk; skipped when it is not built)
    def test_theBuiltJar_carriesAgentAdk(self):
        jar = os.path.join(os.path.dirname(HERE), "agent-service/target/agent-service-0.1.0-SNAPSHOT.jar")
        if not os.path.isfile(jar):
            self.skipTest("build first: ./mvnw -B -ntp -DskipTests package")
        self.assertTrue(hasattr(live, "jar_has_live_model"), "the script checks the jar for agent-adk")
        self.assertTrue(live.jar_has_live_model(jar))


class LeakScanGeminiKey(unittest.TestCase):
    """scripts/h5-leak-scan.sh holds the Gemini key as a fixed pattern (26.6): fed to grep on a pipe like the others,
    over the run's evidence and the branch. Its self-test plants a canary key and must find it."""

    SCAN = os.path.join(HERE, "h5-leak-scan.sh")

    def run_scan(self, *args, env=None):
        return subprocess.run(["bash", self.SCAN] + list(args), capture_output=True, text=True, timeout=120,
                              env=env or {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": os.environ.get("HOME", "")})

    # Covers: 26.6, I8 (the self-test plants a canary Gemini key and finds it by its pattern name, in the evidence)
    def test_theSelfTest_findsAPlantedGeminiKey(self):
        done = self.run_scan("--self-test")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertRegex(done.stdout, r"HIT  the Gemini API key \(evidence\): [1-9]")
        self.assertRegex(done.stdout, r"HIT  a Gemini API key shape \(evidence\): [1-9]")

    # Covers: 26.6, I8 (the dry run names the Gemini key among the values, and never prints it)
    def test_theDryRun_namesTheGeminiKey_andNeverItsValue(self):
        key = "AIzaDRYRUN" + "q" * 25
        env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "GEMINI_API_KEY": key}
        done = self.run_scan("--dry-run", env=env)
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertTrue("the Gemini API key" in done.stdout, "the key among the dry run's values")
        self.assertIn("DRY-RUN shape: a Gemini API key shape", done.stdout)
        self.assertNotIn(key, done.stdout + done.stderr)

    # Covers: 26.6, I8 (the key reaches grep on a pipe, never as an argument: the script never names it in a command)
    def test_theKey_reachesGrepOnAPipe(self):
        with open(self.SCAN) as f:
            text = f.read()
        self.assertTrue("printf 'the Gemini API key\\t%s\\n' \"$GEMINI_API_KEY\"" in text, "the fixed pattern's line")
        self.assertNotRegex(text, r"grep[^\n]*\$GEMINI_API_KEY")


if __name__ == "__main__":
    unittest.main()
