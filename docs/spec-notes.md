# UCP spec notes (pinned 2026-08-25)

Facts the M2 client relies on, read on 2026-11-12 from the raw sources, not from page summaries. They back the VERIFY items in DESIGN.md (2.1, 8, R1, R7, R8).

## Sources

| Source | URL |
| --- | --- |
| OpenAPI (REST binding) | https://ucp.dev/2026-08-25/services/shopping/rest.openapi.json |
| Checkout schema | https://ucp.dev/2026-08-25/schemas/shopping/checkout.json |
| Referenced type schemas | https://ucp.dev/2026-08-25/schemas/common/types/{payment,payment_instrument,message,total,amount,payment_credential}.json and .../shopping/types/{line_item,item,buyer,order_confirmation}.json |
| Checkout REST page | https://ucp.dev/latest/specification/shopping/checkout/rest/ |
| Message signatures page | https://ucp.dev/2026-08-25/specification/signatures/ |
| Conformance README | https://raw.githubusercontent.com/Universal-Commerce-Protocol/conformance/main/README.md |

`info.version` of the OpenAPI document is `2026-08-25`, and every schema `$id` carries `2026-08-25`. The `latest` checkout page also reads 2026-08-25, so the pin matches latest today. None of these sources has a changelog, so "what changed since" cannot be read from them.

## Checkout (REST)

- Operations: `POST /checkout-sessions`, `GET` and `PUT /checkout-sessions/{id}`, `POST /checkout-sessions/{id}/complete`, `POST /checkout-sessions/{id}/cancel`. Every one answers 201 (create) or 200 in the OpenAPI document.
- The same OpenAPI document also defines `/carts`, `/orders/{id}` and the catalog operations (below). The agent uses none of the carts or orders operations in M2.
- Headers: `UCP-Agent` (required, RFC 8941 dictionary carrying the profile URI), `Request-Id` (required, `format: uuid`) and `Idempotency-Key` (required in the OpenAPI parameter, `format: uuid`; the checkout page text says state-changing operations SHOULD support it). `Authorization`, `X-API-Key`, `Signature`, `Signature-Input` and `Content-Digest` are optional.
- Checkout `required` fields: `ucp`, `id`, `line_items`, `status`, `currency`, `totals`, `links`.
- `status` enum has **six** values: `incomplete`, `requires_escalation`, `ready_for_complete`, `complete_in_progress`, `completed`, `canceled`. DESIGN 2.1 and `CheckoutStatus` list five and omit `requires_escalation`. See "Differences".
- Request shape by operation (`ucp_request` markers):
  - create and update: `line_items` required, `buyer`, `context`, `payment` optional.
  - complete: `payment` required; `line_items`, `buyer` and `context` omitted.
- Line item: `id` (create: omit; update: optional), `item.id`, `quantity` (integer, minimum 1), response adds `item.title`, `item.price` and `totals`.
- Buyer: `first_name`, `last_name`, `email`, `phone_number`, all optional. Outside `complete_in_progress`, an update replaces the whole session, so the platform resends every buyer field it wants to keep.
- Payment: `payment.instruments[]`, each an instrument with required `id`, `handler_id`, `type`, plus `credential` (required `type`, handler-specific extras), `billing_address`, `display` and `selected`. The word `payment_data` appears nowhere in the 2026-08-25 schemas.
- Money: `amount` is an integer in ISO 4217 minor units, minimum 0, maximum 9007199254740991. `totals` is an array of `{type, amount, display_text?}`; well-known types are subtotal, items_discount, discount, fulfillment, tax, fee, total.
- Order: `{id, permalink_url}` required, `label` optional.
- Messages are one of error, warning or info (`message.json` is a `oneOf`).
- Update during `complete_in_progress`: the business MUST leave the checkout unchanged and return the current checkout with a recoverable error message. A platform MUST NOT start an update in that state.
- Create with every item unavailable: HTTP 200 and the UCP envelope `{ucp: {version, status: "error"}, messages: [...]}`, no checkout resource and no `id`. The page's examples use the codes `out_of_stock` and `item_unavailable`.
- Protocol errors: 401, 403, 409, 429, 503 with a JSON body `{code, content}`. The status table also lists 422, 424 (both discovery failures) and 500.
- Business outcomes are HTTP 200 with a `messages` array.

## Idempotency (signatures page)

- Servers store the key with the result for at least 24 hours (48 recommended).
- Key entropy: at least 128 bits (for example UUID v4, or 22 or more alphanumeric characters). Our key is `ucp-` plus 26 base32 characters, 130 bits.
- Uniqueness: per client and per operation type.
- A duplicate with a matching payload returns the cached response and does not execute again. The payload is compared by the SHA-256 of the raw body bytes.
- A duplicate with a different payload is rejected with 409.
- Platforms MUST generate a fresh key whenever they change the request payload (retries with changed instruments, addresses, items). This agrees with D3 and I7.
- On storage failure the server fails closed with 503.
- **Still unverified (R8):** how a business answers a duplicate that arrives while the first request is still running. The sections read do not say. The lab merchant makes the duplicate wait and share the result.
- **Still unverified (R7):** whether a key is scoped per operation type only or also per path. The spec says "per-operation type"; the lab merchant scopes by method and path.

## Catalog (read later, for CatalogClient)

Operations in the OpenAPI document, all `POST` and all answering 200: `/catalog/search` (`search_catalog`), `/catalog/lookup` (`lookup_catalog`) and `/catalog/product` (`get_product`). Schemas are `catalog_search_request`, `catalog_search_response`, `catalog_lookup_request`, `catalog_lookup_response`, `catalog_get_product_request` and `catalog_get_product_response`. Field level shapes (the product object) are not yet transcribed here; they are read in the CatalogClient commit.

## Conformance suite

- Repository: https://github.com/Universal-Commerce-Protocol/conformance (pytest, run with `uv`). Setup `uv sync`.
- Run: `SERVER_URL=... SIMULATION_SECRET=... uv run pytest --conformance_input=... --fixture_config=...`, or `uv run ucp-conformance --server_url=... --simulation_secret=... --suite=all` (options `--platform`, `-k/--filter`, `--dry-run`).
- Inputs: `conformance_input.json` (merchant values) and an optional `test_fixtures.json`.
- The README's example `conformance_input.json` has `"ucp_version": "2026-04-08"`. Its worked example runs against a Python sample server from the `samples` and `python-sdk` repositories.
- What the server must do with `SIMULATION_SECRET` is not documented in the README. That is the R3 question and needs the suite source.

## Drift recorded (T)

| Where | Value | Meaning |
| --- | --- | --- |
| Checkout REST page, two error-envelope examples (create with every item unavailable) | `ucp.version` is `2026-01-11` | An example not updated to the pin. We ignore the version inside error envelopes |
| Conformance README, example `conformance_input.json` | `ucp_version` is `2026-04-08` | The suite example targets an older version than our pin. Also the version R12 mentions for a real plugin |
| OpenAPI parameters `Idempotency-Key` and `Request-Id` | `format: uuid` | Our key (`ucp-` plus base32, 7.4) and a generated Request-Id differ in format. The Request-Id is UUID-formatted by decision H. The key keeps the contract in 7.4; entropy meets the spec's minimum |

## Differences between the sources and the design or merchant

| Item | Source says | Design or merchant | Handling |
| --- | --- | --- | --- |
| Complete payment | `payment.instruments[]` | `CheckoutWire.completeBody` emits `payment_data`. The merchant reads `payment.instruments[0]` | Core fix in its own commit (M) |
| Status set | six values, including `requires_escalation` | five in DESIGN 2.1 and `CheckoutStatus` | Unknown to the parser: `Uncertain` on a mutating call, `ProtocolError` on get (8.1), until the contract adds it |
| All-out-of-stock create | 200, `ucp.status: error`, no id | `CheckoutView.id` is non-null | `CallOutcome.Rejected` (N) |
| Line ids on update | optional on update | `ProposedLine` has no id. The merchant's `resolveLines` ignores line ids | Sent from the last `CheckoutView` in the gateway commit (L) |
| Merchant status | six values on the wire | The merchant never emits `requires_escalation` | None needed |
