#!/usr/bin/env python3
"""The live spike against the pinned UCP sample merchant (H1, notes/h1/h1-plan.md C1). Standard library only.

Run by scripts/interop-spike.sh, which starts the sample on 127.0.0.1 and passes the URL and the two SQLite files.
It plays the agent's calls (the bodies are what agent-core's CheckoutWire builds) and records every request and response
under docs/interop/fixtures/, normalised: ids, timestamps and tokens are replaced by fixed placeholders. It prints one
OBSERVED line per VERIFY item of the plan; docs/interop/spike.md is written from them. It fails with a clear message
when a field it needs is missing from a recorded response.

Usage: interop_spike.py BASE_URL PRODUCTS_DB TRANSACTIONS_DB FIXTURE_DIR
"""
import copy
import http.server
import json
import re
import sqlite3
import sys
import threading
import urllib.error
import urllib.request
import uuid

BASE, PRODUCTS_DB, TX_DB, FIXDIR = sys.argv[1].rstrip("/"), sys.argv[2], sys.argv[3], sys.argv[4]
BUYER = "john.doe@example.com"          # the sample's own fixture customer (test_data/flower_shop/customers.csv)
ITEM = "bouquet_roses"                  # the sample's fixture product (3500 minor units)

UUID = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
STAMP = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})?")
SEEN = {}                                # uuid -> placeholder, stable across the whole run
STEP = [0]
OBSERVED = []


class SpikeFailure(Exception):
    pass


def fail(msg):
    raise SpikeFailure(msg)


def normalise_text(s):
    def sub(m):
        return SEEN.setdefault(m.group(0).lower(), "<id-%d>" % (len(SEEN) + 1))
    s = UUID.sub(sub, s)
    s = re.sub(r"127\.0\.0\.1:\d+", "127.0.0.1:<port>", s)
    return STAMP.sub("<timestamp>", s)


def normalise(o, key=""):
    if isinstance(o, dict):
        return {k: ("<token>" if k == "token" and isinstance(v, str) else normalise(v, k)) for k, v in o.items()}
    if isinstance(o, list):
        return [normalise(v, key) for v in o]
    if isinstance(o, str):
        return normalise_text(o)
    return o


def get(o, path, step):
    """o[path] with dots and list indexes, or a clear failure naming the step."""
    cur = o
    for part in path.split("."):
        try:
            cur = cur[int(part)] if isinstance(cur, list) else cur[part]
        except (KeyError, IndexError, TypeError, ValueError):
            fail("step '%s': the recorded response has no field '%s'" % (step, path))
    return cur


def observed(item, text):
    OBSERVED.append((item, text))
    print("OBSERVED %-34s %s" % (item, text), flush=True)


# ------------------------------------------------------------------ a local agent profile, which the sample fetches
class ProfileHandler(http.server.BaseHTTPRequestHandler):
    hits = []

    def do_GET(self):
        ProfileHandler.hits.append(self.path)
        body = json.dumps({"ucp": {"version": "2026-08-25", "capabilities": {
            "dev.ucp.shopping.checkout": [{"version": "2026-08-25"}]}}}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


profile_server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), ProfileHandler)
threading.Thread(target=profile_server.serve_forever, daemon=True).start()
AGENT = 'profile="http://127.0.0.1:%d/agent"' % profile_server.server_address[1]


# ------------------------------------------------------------------ requests
def call(name, method, path, body=None, agent=AGENT, mutating=None, key=None, record=True):
    STEP[0] += 1
    headers = {"UCP-Agent": agent, "Request-Id": str(uuid.uuid4()), "Accept": "application/json"}
    if mutating is None:
        mutating = method in ("POST", "PUT")
    if mutating:
        headers["Idempotency-Key"] = key or str(uuid.uuid4())
    data = None
    if body is not None:
        data = json.dumps(body, separators=(",", ":")).encode()
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=20) as r:
            status, rheaders, raw = r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        status, rheaders, raw = e.code, dict(e.headers), e.read()
    try:
        parsed = json.loads(raw) if raw else None
    except ValueError:
        parsed = {"_raw": raw.decode("utf-8", "replace")[:300]}
    if record:
        fixture = {
            "request": {"method": method, "path": normalise_text(path),
                        "headers": {k: normalise_text(v) if k.lower() != "ucp-agent" else normalise_text(
                            re.sub(r"127\.0\.0\.1:\d+", "127.0.0.1:<port>", v)) for k, v in headers.items()},
                        "body": normalise(body) if body is not None else None},
            "response": {"status": status,
                         "headers": {k.lower(): v for k, v in rheaders.items() if k.lower() in ("content-type", "location")},
                         "body": normalise(parsed)},
        }
        with open("%s/%02d-%s.json" % (FIXDIR, STEP[0], name), "w") as f:
            json.dump(fixture, f, indent=2, sort_keys=True)
            f.write("\n")
    return status, parsed


def line_ids(checkout):
    return [li["id"] for li in checkout["line_items"]]


def update_body(checkout, fulfillment=None, buyer=True):
    """A PUT is a full replacement (8.4): the line ids from the last view, the buyer, and the fulfillment selection."""
    body = {"line_items": [{"id": li["id"], "item": {"id": li["item"]["id"]}, "quantity": li["quantity"]}
                           for li in checkout["line_items"]]}
    if buyer:
        body["buyer"] = {"email": BUYER}
    if fulfillment is not None:
        body["fulfillment"] = fulfillment
    return body


def create(name, qty=1, agent=AGENT, buyer=True):
    body = {"line_items": [{"item": {"id": ITEM}, "quantity": qty}]}
    if buyer:
        body["buyer"] = {"email": BUYER}
    return call(name, "POST", "/checkout-sessions", body, agent=agent)


def drive_to_ready(tag, qty=1):
    """Create, then the fulfillment steps of the sample's own client, until ready_for_complete. Returns the checkout."""
    status, co = create(tag + "-create", qty)
    if status not in (200, 201):
        fail("step '%s-create': create answered %s" % (tag, status))
    get(co, "id", tag + "-create")
    get(co, "status", tag + "-create")
    ids = line_ids(co)
    method = {"id": "method_1", "type": "shipping", "line_item_ids": ids}
    status, co = call(tag + "-fulfillment-1-methods", "PUT", "/checkout-sessions/" + co["id"],
                      update_body(co, {"methods": [method]}))
    if status != 200:
        fail("step '%s-fulfillment-1-methods': update answered %s" % (tag, status))
    methods = get(co, "fulfillment.methods", tag + "-fulfillment-1-methods")
    destinations = methods[0].get("destinations") or []
    if not destinations:
        fail("step '%s-fulfillment-1-methods': no destinations offered to the fixture customer" % tag)
    method = dict(method, selected_destination_id=destinations[0]["id"])
    status, co = call(tag + "-fulfillment-2-destination", "PUT", "/checkout-sessions/" + co["id"],
                      update_body(co, {"methods": [method]}))
    if status != 200:
        fail("step '%s-fulfillment-2-destination': update answered %s" % (tag, status))
    groups = get(co, "fulfillment.methods.0.groups", tag + "-fulfillment-2-destination")
    options = groups[0].get("options") or []
    if not options:
        fail("step '%s-fulfillment-2-destination': no options offered after the destination" % tag)
    method = dict(method, groups=[{"id": groups[0]["id"], "line_item_ids": ids, "selected_option_id": options[0]["id"]}])
    status, co = call(tag + "-fulfillment-3-option", "PUT", "/checkout-sessions/" + co["id"],
                      update_body(co, {"methods": [method]}))
    if status != 200:
        fail("step '%s-fulfillment-3-option': update answered %s" % (tag, status))
    if get(co, "status", tag + "-fulfillment-3-option") != "ready_for_complete":
        fail("step '%s-fulfillment-3-option': status is %r, not ready_for_complete" % (tag, co["status"]))
    return co


def pay(token):
    return {"payment": {"instruments": [{"id": "instr_spike", "handler_id": "mock_payment_handler", "type": "card",
                                         "credential": {"type": "token", "token": token}}]}}


def db(path, sql, args=()):
    c = sqlite3.connect(path)
    try:
        rows = c.execute(sql, args).fetchall()
        c.commit()
        return rows
    finally:
        c.close()


def totals_of(co):
    return {t["type"]: t["amount"] for t in co.get("totals", [])}


# ------------------------------------------------------------------ the spike
def main():
    # 1. discovery
    status, prof = call("discovery", "GET", "/.well-known/ucp", mutating=False)
    if status != 200:
        fail("step 'discovery': answered %s" % status)
    ucp = get(prof, "ucp", "discovery")
    shopping = get(ucp, "services", "discovery").get("dev.ucp.shopping")
    if not shopping:
        fail("step 'discovery': the recorded response has no service 'dev.ucp.shopping'")
    endpoint = get(shopping, "0.endpoint", "discovery")
    handlers = {h["id"] for hs in get(ucp, "payment_handlers", "discovery").values() for h in hs}
    if "mock_payment_handler" not in handlers:
        fail("step 'discovery': no mock_payment_handler among %s" % sorted(handlers))
    observed("discovery.version", "ucp.version=%s, service version=%s" % (
        get(ucp, "version", "discovery"), get(shopping, "0.version", "discovery")))
    observed("discovery.endpoint", "%s (path prefix %r); transports=%s" % (
        endpoint, re.sub(r"^https?://[^/]+", "", endpoint),
        sorted({s["transport"] for s in shopping})))
    observed("discovery.capabilities", ", ".join(sorted(get(ucp, "capabilities", "discovery"))))
    observed("discovery.payment_handlers", ", ".join(sorted(handlers)))

    # 2 and 8. a create with buyer.email only, and one with no buyer
    status, co = create("create-buyer-email-only")
    observed("create.buyer_email_only", "status %s, checkout status %r, id present %s" % (
        status, co.get("status") if co else None, bool(co and co.get("id"))))
    status2, co2 = create("create-no-buyer", buyer=False)
    observed("create.no_buyer", "status %s, checkout status %r" % (status2, co2.get("status") if co2 else None))
    observed("create.totals_and_links", "totals=%s; continue_url=%s; keys=%s" % (
        totals_of(co) if co else None, "continue_url" in (co or {}), sorted((co or {}).keys())))

    # 2. one checkout through the fulfillment steps, then completed
    ready = drive_to_ready("happy")
    observed("fulfillment.steps", "three PUTs: methods, destination, option: ready_for_complete; totals=%s" % totals_of(ready))
    observed("ready.status_for_buyer_email_only", "create (buyer email, no fulfillment) -> %r" % co.get("status"))
    before = db(TX_DB, "select count(*) from orders")[0][0]
    status, done = call("happy-complete", "POST", "/checkout-sessions/%s/complete" % ready["id"], pay("success_token"))
    if status != 200:
        fail("step 'happy-complete': answered %s: %s" % (status, json.dumps(done)[:200]))
    order_id = get(done, "order.id", "happy-complete")
    orders = db(TX_DB, "select id, data from orders")
    observed("complete.success", "status %s, checkout status %r, order id present; orders table %d -> %d rows" % (
        status, done["status"], before, len(orders)))
    observed("ground_truth.orders_table", "orders(id, data JSON); data keys=%s" % sorted(json.loads(orders[0][1]).keys()))

    # 6. GET after completion
    status, got = call("get-after-complete", "GET", "/checkout-sessions/" + ready["id"], mutating=False)
    observed("get.after_completion", "status %s, checkout status %r, order id %s" % (
        status, got.get("status"), (got.get("order") or {}).get("id") == order_id))

    # 3. price UPDATE, re-read without a restart
    c3 = drive_to_ready("price")
    t0 = totals_of(c3)
    old = db(PRODUCTS_DB, "select price from products where id=?", (ITEM,))[0][0]
    db(PRODUCTS_DB, "update products set price=? where id=?", (old + 100, ITEM))
    status, again = call("price-get-after-update", "GET", "/checkout-sessions/" + c3["id"], mutating=False)
    t1 = totals_of(again)
    status, put = call("price-put-after-update", "PUT", "/checkout-sessions/" + c3["id"], update_body(c3, {"methods": c3["fulfillment"]["methods"]}))
    t2 = totals_of(put) if status == 200 else {}
    status, paid = call("price-complete-after-update", "POST", "/checkout-sessions/%s/complete" % c3["id"], pay("success_token"))
    observed("price.update_seen_by_get", "total before %s, GET after UPDATE %s: %s" % (
        t0.get("total"), t1.get("total"), "seen" if t0.get("total") != t1.get("total") else "NOT seen"))
    observed("price.update_seen_by_put", "PUT after UPDATE total %s: %s" % (t2.get("total"), "seen" if t2.get("total") != t0.get("total") else "NOT seen"))
    last = db(TX_DB, "select data from orders order by rowid desc limit 1")
    observed("price.complete_after_update", "status %s, checkout status %r, code %s; order data total %s" % (
        status, (paid or {}).get("status"), [m.get("code") for m in (paid or {}).get("messages", [])],
        (json.loads(last[0][0]).get("totals") if last else None)))
    db(PRODUCTS_DB, "update products set price=? where id=?", (old, ITEM))

    # 3b. a price UPDATE after the quote and NO re-read: does complete charge the old quote or the new price?
    c3b = drive_to_ready("price-direct")
    db(PRODUCTS_DB, "update products set price=? where id=?", (old + 200, ITEM))
    status, direct = call("price-complete-direct", "POST", "/checkout-sessions/%s/complete" % c3b["id"], pay("success_token"))
    last = db(TX_DB, "select data from orders order by rowid desc limit 1")
    charged = json.loads(last[0][0]).get("totals") if last else None
    observed("price.complete_without_reread", "quote total %s, UPDATE +200, complete: status %s, checkout status %r, codes %s, order totals %s" % (
        totals_of(c3b).get("total"), status, (direct or {}).get("status"),
        [m.get("code") for m in (direct or {}).get("messages", [])], charged))
    db(PRODUCTS_DB, "update products set price=? where id=?", (old, ITEM))

    # 4. stock UPDATE to 0 then complete
    c4 = drive_to_ready("stock")
    db(TX_DB, "update inventory set quantity=0 where product_id=?", (ITEM,))
    status, out = call("stock-complete-sold-out", "POST", "/checkout-sessions/%s/complete" % c4["id"], pay("success_token"))
    observed("stock.complete_when_sold_out", "status %s, body status %r, codes %s" % (
        status, (out or {}).get("status"), [m.get("code") for m in (out or {}).get("messages", [])] or (out or {}).get("code")))
    db(TX_DB, "update inventory set quantity=1000 where product_id=?", (ITEM,))

    # 5. fail_token
    c5 = drive_to_ready("decline")
    status, out = call("decline-complete-fail-token", "POST", "/checkout-sessions/%s/complete" % c5["id"], pay("fail_token"))
    observed("payment.fail_token", "status %s, body status %r, codes %s" % (
        status, (out or {}).get("status"), [m.get("code") for m in (out or {}).get("messages", [])] or (out or {}).get("code")))
    status, after = call("decline-get-after", "GET", "/checkout-sessions/" + c5["id"], mutating=False)
    observed("payment.after_decline_get", "status %s, checkout status %r" % (status, (after or {}).get("status")))

    # 7. a 404
    status, nf = call("get-unknown-id", "GET", "/checkout-sessions/does-not-exist", mutating=False)
    observed("get.unknown_id", "status %s, body %s" % (status, json.dumps(nf)[:160]))

    # 9. versions in UCP-Agent
    status, a = create("version-2026-04-08", agent=AGENT + '; version="2026-04-08"')
    observed("version.agent_2026-04-08", "create status %s" % status)
    status, b = create("version-2026-08-25", agent=AGENT + '; version="2026-08-25"')
    observed("version.agent_2026-08-25", "create status %s (the server speaks %s)" % (status, ucp["version"]))
    status, c = create("version-2099-01-01", agent=AGENT + '; version="2099-01-01"')
    observed("version.agent_2099-01-01", "create status %s, body %s" % (status, json.dumps(c)[:200]))

    observed("agent_profile.fetched_by_server", "%d GET(s) of our profile URL: %s" % (len(ProfileHandler.hits), sorted(set(ProfileHandler.hits))))
    # the sample's own listing of what it logged, as evidence of what reached it
    observed("ground_truth.request_logs_rows", str(db(TX_DB, "select count(*) from request_logs")[0][0]))
    with open(FIXDIR + "/observed.json", "w") as f:
        json.dump([{"item": i, "observed": normalise_text(t)} for i, t in OBSERVED], f, indent=2)
        f.write("\n")


try:
    main()
except SpikeFailure as e:
    print("FAIL: %s" % e, file=sys.stderr)
    sys.exit(1)
finally:
    profile_server.shutdown()
