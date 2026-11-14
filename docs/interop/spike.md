# Interop spike: the official Python sample merchant (H1, C1)

Run with `scripts/interop-fetch.sh` then `scripts/interop-spike.sh`. The merchant is the UCP `samples` repo,
`rest/python/server`, at the commit in `pin.txt` (`52319dffc1e4c521debc68fcc841e48c7a8bf80d`), dependencies from our own
hash-locked `uv.lock` (`uv sync --frozen`, uv 0.13.7), bound to 127.0.0.1 on a free port by `scripts/interop_serve.py`
(the sample's own `server.py` binds 0.0.0.0). The spike plays the calls our agent makes with the stdlib only. The
normalised request/response pairs are in `fixtures/` (ids, timestamps, tokens and the port are replaced), the one-line
findings are in `fixtures/observed.json`. Nothing here runs inside `verify`. The sample was not modified.

## Answers

| # | Question | Observed (fixture) |
| --- | --- | --- |
| 1 | Version | Discovery says `ucp.version` **2026-04-08**, REST service version 2026-04-08 (01). Our agent's profile check wants exactly 2026-08-25, so today it would suspend this merchant (G1) |
| 2 | Endpoint | `endpoint` is the origin, **no path prefix**, transport `rest` only (01). Routes are `/checkout-sessions`, `/checkout-sessions/{id}` (no `/ucp/v1`) |
| 3 | Capabilities | checkout, cart, order, discount, fulfillment, buyer_consent. **No catalog** (01). Payment handlers: `google_pay`, `mock_payment_handler`, `shop_pay` |
| 4 | Create | POST with line items by item id and a buyer email, or with **no buyer at all**: 201, status `incomplete`, totals subtotal+total, `links`, no `continue_url` (02, 03, 04) |
| 5 | Readiness | `ready_for_complete` only after fulfillment is chosen. Buyer email alone stays `incomplete` (04) |
| 6 | Fulfillment | Three full-replacement PUTs: (a) `fulfillment.methods=[{id, type:"shipping", line_item_ids}]` returns `destinations` (`addr_1`, `addr_2`); (b) add `selected_destination_id` returns `groups[].options` (`std-ship` free, `exp-ship-us` 1500); (c) add `groups:[{id, line_item_ids, selected_option_id}]` returns `ready_for_complete` with a `fulfillment` total line (05, 06, 07). A PUT must repeat `line_items` with their ids |
| 7 | Complete | `payment.instruments[{id, handler_id:"mock_payment_handler", type:"card", credential:{type:"token", token}}]`; 200, status `completed`, `order` with id and `permalink_url` (08). GET afterwards still says `completed` with the order (09) |
| 8 | Ground truth | Orders table: one row per completed checkout, `data` JSON (checkout_id, currency, fulfillment, line_items, totals). Rows 0 to 1 on the happy path, still 0 after a decline or a sold-out complete |
| 9 | Price change | A GET after a DB price UPDATE **does not show it** (total stays 3500). A PUT does (3600) and complete then charges 3600. **Complete without a re-read charges the old quote (3500) silently**: 200, `completed`, no message, order totals 3500 (10 to 21) |
| 10 | Stock | UPDATE to 0 then complete: **409**, code `OUT_OF_STOCK`, no order (22 to 26) |
| 11 | Decline | Token `fail_token`: **402**, code `INSUFFICIENT_FUNDS`. The checkout stays `ready_for_complete` and a retry is possible (27 to 32) |
| 12 | Unknown id | 404, `RESOURCE_NOT_FOUND`, body `{"ucp":{"version","status":"error"},"messages":[...]}` (33) |
| 13 | Versions | `UCP-Agent` `version=2026-04-08`: 201. `2026-08-25`: **422** (the server is older). `2099-01-01`: 422 `VERSION_UNSUPPORTED` (34 to 36) |
| 14 | Our profile | The server **fetches the `profile=` URL** on creates and updates (24 GETs in the run, against a local stub served by the spike) |
| 15 | Idempotency | Not probed beyond the headers being accepted; the sample logs every request in `request_logs` (23 rows) |

## Where the plan was wrong or unproven

1. **Price on complete (plan section 8, "VERIFY: does it return `incomplete`/`price_changed` or silently charge").**
   It silently charges the quote the checkout already holds, and a GET does not refresh it. So with this merchant a
   reconciliation GET cannot see a price change and `price_unstable` can only come from a PUT. The S5 scenarios need
   the harness to expect a charge at the old quote, not a refusal.
2. **Status of a completed checkout is read by GET (G-reconcile).** Confirmed working (09), but GET never re-prices (point 1).
3. **Fulfillment is not optional for this merchant.** The plan already says so. Observed detail it lacked: the third
   PUT must carry `groups[].selected_option_id` with the group id from the second response, and every PUT must repeat
   the line item ids. C6 should build the update from the previous response, not from a template.
4. **`version="2026-08-25"` in `UCP-Agent` is refused** (422), which the plan expected only for a version newer than the
   server's. Negotiation must therefore send the **merchant's** version, not ours.
5. Not wrong, now confirmed: version 2026-04-08, no path prefix, no catalog, `mock_payment_handler`/card/token shape,
   409 `OUT_OF_STOCK`, the profile fetch, orders table as ground truth.
