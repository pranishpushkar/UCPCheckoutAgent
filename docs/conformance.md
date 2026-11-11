# Conformance spike (M2)

Run on 2026-11-12 with `scripts/run-conformance.sh` against the lab merchant (`merchant/` after the catalog commit).
It answers R3 in section 17 ("the suite may need a simulation hook the merchant lacks"): **yes, it does**, and more
than a hook. Nothing in `merchant/` was changed to make the suite pass.

## What was run

| Item | Value |
| --- | --- |
| Suite | https://github.com/Universal-Commerce-Protocol/conformance at `1201fb78e85217c5f2df060066ccbb0a1d1dce0b` (main, 2026-11-10) |
| SDK | `python-sdk` tag `v2026-04-08-6` (ucp-sdk 0.4.6), the pair the suite's own CI uses. A `v2026-08-25` SDK tag exists; it was not tried |
| Command | `ucp-conformance --suite=all --conformance_input=... --fixture_config=...` (the script writes both files into `.conformance/`) |
| Our inputs | `ucp_version` 2026-08-25, `required_capabilities` only `dev.ucp.shopping.checkout`, item `item_123` at 2500, `item_789` sold out through `/_lab/stock`, `no_such_item` as the non-existent item |
| Tools | uv 0.13.7, Python 3.11 (managed by uv) |

Result: **78 tests: 6 passed, 5 failed, 40 errors, 27 skipped.** The raw output of the run is in
`.conformance/results/` (not committed).

## What passed (6)

| Test | What it shows |
| --- | --- |
| `protocol_test.test_discovery` | Profile is wrapped in `ucp`, validates against the SDK model, version equals the one in our input, capability names are reverse-DNS. Passes only because our input says 2026-08-25 (see gap S1) |
| `business_logic_test.test_totals_calculation_on_create` | Totals from the merchant's own price, with `select_fulfillment=False` |
| `idempotency_test.test_idempotency_create` | Same key and body replays, same key and a different body is 409 |
| `validation_test.test_out_of_stock` | All items unavailable: 200, `ucp.status: error`, no `id`, code `out_of_stock` |
| `validation_test.test_structured_error_messages` | Error messages carry type, code, content and severity |
| `validation_test.test_complete_without_fulfillment` | Completing a checkout that is not ready (the merchant lacks a buyer email, not fulfillment) is refused in-band with code `missing`, which the test accepts |

## What failed, and why

### Failures (5)

| Test | Cause | Whose |
| --- | --- | --- |
| `protocol_test.test_version_negotiation` | The suite sends `UCP-Agent: profile="..."; version="2099-01-01"` and expects **422**. The merchant ignores the version and answers 201 | **Ours.** The merchant has no version negotiation. Section 26.2 plans it for the agent side; the merchant side is not planned. Not needed for M2 |
| `validation_test.test_product_not_found` | Expects the code `item_unavailable` or `not_found` for a non-existent item. The merchant says `out_of_stock` | **Ours**, a naming choice in the merchant. The suite accepts either of two codes, and the merchant uses a third |
| `business_logic_test.test_buyer_consent` | Expects `buyer.consent` to be persisted and returned | **Ours** (no `dev.ucp.shopping.buyer_consent`), but the test is not gated on the capability, so it fails instead of skipping: **suite** |
| `business_logic_test.test_multiple_discounts_one_rejected` | Expects applied discounts | **Ours** (no `dev.ucp.shopping.discount`), not gated either: **suite** |
| `fulfillment_test.test_fulfillment_flow` | Expects `fulfillment.methods` after an update | **Ours** (no fulfillment). Not gated either: **suite** |

### Errors (40)

- **37 are `KeyError: 'fulfillment'`.** The helper every checkout test uses, `create_checkout_session`, defaults to
  `select_fulfillment=True` and then drives a fulfillment selection (`ensure_fulfillment_ready` reads
  `checkout["fulfillment"]["methods"][0]`). The merchant has no fulfillment capability (A.4), so these tests die in
  setup. The test classes are gated only on `dev.ucp.shopping.checkout`, so a checkout-only merchant gets **errors,
  not skips**. This is a **suite** limitation first (a checkout-only business is a legal profile), and a gap of **ours**
  second (no fulfillment). The effect is large: almost the whole checkout lifecycle, totals, the idempotency tests for
  update, complete and cancel (R7 and R8 in section 17 stay open), the payment tests and the order tests never reach the
  merchant's own behaviour, so this run says nothing about them.
- **3 are `AttributeError: 'NoneType' object has no attribute 'instruments'`** (`business_logic_test`:
  `test_buyer_info_persistence`, `test_discount_flow`, `test_totals_recalculation_on_update`). The test reads
  `checkout.payment.instruments` from the create response and resends it. The merchant's checkout response has no
  `payment` object. **Checked against the raw 2026-08-25 `checkout.json`:** `required` is `ucp, id, line_items, status,
  currency, totals, links`. `payment` is not required in a response (its `ucp_request` markers are create and update
  optional, complete required). The SDK model the suite uses (`ucp-sdk` 0.4.6) also types it `Payment | None = None`.
  So the merchant is **not** at fault: the response is valid, and the test dereferences an optional field without a
  check. These 3 errors are the suite's, not a merchant gap.

### Skipped (27)

| Count | Reason |
| --- | --- |
| 6 | Capability `dev.ucp.shopping.order` not advertised |
| 6 | Capability `dev.ucp.shopping.discount` not advertised |
| 2 | Capability `dev.ucp.shopping.fulfillment` not advertised |
| 12 | Fixtures we did not configure (known customers, stored addresses, free shipping, dynamic fulfillment, second and fixed discount codes) |
| 1 | `test_discovery_urls`: hard-coded skip, "Schemas not yet published on remote ucp.dev domain" |

## Fulfillment

Fulfillment: not added in M2. The suite's create helper always drives fulfillment and its tests are gated only on
checkout, so a checkout-only merchant errors instead of skipping (suite issue). Conformance is revisited in M7 with the
order capability, webhooks and the simulation hook.

## What `SIMULATION_SECRET` requires from the server (R3)

Read from `shopping/order/simulation_url_security_test.py`, `webhook_test.py`, `webhook_structure_test.py` and
`framework/base_test.py`. The suite does not use the secret anywhere else.

1. **An endpoint outside UCP**: `POST /testing/simulate-shipping/{order_id}`, at the server root (not under the
   `/ucp/v1` checkout endpoint), taking the order id from a completed checkout.
2. **A shared secret in a header.** The suite sends `Simulation-Secret: <value of --simulation_secret>`. The server
   must answer **403** when the header is missing or wrong and **200** when it matches. Server and suite are started
   with the same value (the sample server takes `--simulation_secret`). If the flag and `SIMULATION_SECRET` are both
   unset the suite invents a random UUID per run, and no server can match it.
3. **A state change and events.** A 200 moves the order to shipped. With the order capability, the business must then
   send an update webhook carrying the full order entity with a `shipped` fulfillment event, after an earlier
   "Order created" webhook, to the webhook URL in the platform profile the suite serves on localhost (webhook port
   8284, agent profile port 8285). Deliveries are signed and carry digest headers.
4. **Skip rules.** `webhook_structure_test` skips if the endpoint answers 403 (secret mismatch) or 404 or 405 (no
   simulation path). `simulation_url_security_test` and `webhook_test` do not skip: they fail.

Our merchant has none of this. It has `/_lab/*` instead (loopback only, no secret, A.3), no order capability, no
orders endpoint under UCP and no webhooks. The lab does not need them: reconciliation reads the checkout (7.5), and
Order plus webhooks are M7. The secret is **not** a credential the merchant must check for any checkout call.

## Gaps: ours or the suite's

| # | Gap | Whose | Action |
| --- | --- | --- | --- |
| S1 | The suite and SDK target **2026-04-08**; we target **2026-08-25**. The README example, the SDK pin (`ucp-sdk==0.4.6`) and the suite's CI checkout are all 2026-04-08. We got `test_discovery` through by declaring 2026-08-25 in our input, but every response is parsed with SDK models of the older release | Suite's version | Try the `v2026-08-25` SDK tag and a suite revision that pins it; otherwise accept that a pass here means "conformant to 2026-04-08 shapes". R12 and 26.2 stand |
| S2 | Checkout tests need **fulfillment** and error (not skip) without it | Suite (not gated), and ours (no capability) | Not added in M2. Revisited in M7 |
| O1 | `dev.ucp.shopping.order`, webhooks and the **simulation hook** (`/testing/simulate-shipping`, `Simulation-Secret`) | Ours | M7 (Order and webhooks). Not in M2 |
| O2 | Discount, buyer consent | Ours | None planned. Skipped or failed, and out of scope (N1 to N5) |
| O3 | No version negotiation on the merchant (422 for an unsupported `UCP-Agent` version) | Ours | A small merchant change if we want it, later |
| O4 | `out_of_stock` used for an unknown item | Ours | Would be `not_found` or `item_unavailable` in the suite's list; trivial, not done in M2 |
| O5 | No `payment` object in the checkout response | Suite (optional field in 2026-08-25 and in the SDK model) | None |
| R1 | README says an out-of-stock item gives **409**; the suite's code accepts **either** a 4xx **or** a 200 with `ucp.status: error`, no `id` and an `out_of_stock` or `item_unavailable` message | Suite's README is stale | None. The merchant's 200 matches the spec page (spec-notes) and passes `test_out_of_stock` |

## Reproducing

```
scripts/run-conformance.sh                    # builds and starts the merchant on a free port, runs the suite
scripts/run-merchant.sh 8080 &                # or start the merchant yourself
scripts/run-conformance.sh http://127.0.0.1:8080
```

The script clones the suite and SDK into `.conformance/` (gitignored) at the pinned revisions, writes the two input
files, sets `item_789` to stock 0 through `/_lab/stock`, and tees the output to `.conformance/results/`. Its exit
status is the suite's; the summary block at the end has the counts. Beware relative paths: the suite runs from its own
directory, so an input path must be absolute.
