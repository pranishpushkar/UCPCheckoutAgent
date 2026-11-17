"""Tests of shopify_live.py's probe checkout against a fake store (never Shopify): the probe is canceled on every way out
of it, or, when it cannot be, the script says so. Run by hand: python3 -I scripts/shopify_live_test.py
Not part of ./mvnw verify (verify needs no Python)."""
import io
import json
import os
import signal
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


if __name__ == "__main__":
    unittest.main()
