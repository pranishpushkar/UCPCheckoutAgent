# H5: one live run at a Shopify development store

Filled in from the Stage 3 run (`scripts/shopify-live.sh`, [shopify-live.md](../interop/shopify-live.md)).

## What ran

- Date: 2026-11-17. Commit: `0b11bc7`. Model: the scripted model (`AGENT_MODEL=scripted`).
- The store: the owner's development store, reached over MCP (`transport: mcp`, `completion: handoff`, `owned: true`,
  `requires_buyer_ip: true`), at UCP 2026-04-08.
- Query: `snowboard`. Item: variant `51192065753174`, quantity 1, unit price 699.95 USD (69995 minor).

## The path, end to end

| Step | Result |
| --- | --- |
| Search at the store | ok (the count is not recorded) |
| Quote (`create_checkout`) | status `incomplete`, total 69995 USD |
| Approval cards shown | 1 (approved; CONSUMED at hand-off) |
| Re-quotes (`requote_count`) | 0 |
| `get_checkout` at hand_off | same as approved |
| Final state | `HANDED_OFF` |
| Session cancelled after the owner saw the page (VERIFY 5) | HTTP 200, status `canceled` |

The run left nothing open: the probe checkout was canceled, and there were no owed-cancel warnings at cleanup.

### The two runs before it

1. **First run:** `FAILED_NOT_CHARGED`, `quote_retries_exhausted`. The run kept no evidence that said why. The script
   now keeps it (`04346d9`) and waits for owed cancels before stopping the service (`b2ddf32`).
2. **Second run:** the store refused the create with `buyer_identity_email_is_invalid` (unrecoverable) because the agent
   sent the local sign-in's email. The refusal came as `isError: true`, which the transport read as an unreadable
   answer; it was retried 4 times, and the orphan probe then opened the circuit breaker. Fixed by sending no buyer
   identity at a handoff merchant (`0b11bc7`). A refusal like it is now a definite answer (`ded75dc`), an answer from the
   store no longer counts toward the circuit breaker (`8718551`), and a probe the breaker refuses neither counts nor
   writes a row, ending at a 10-minute deadline (item 3b).

## What the buyer saw

The handoff URL opened the store's cart page showing one snowboard (variant …753174) at $699.95, matching the approval
card. Not rechecked after the cancel.

**The buyer may not be able to finish.** Every answer carried `delivery_no_delivery_available_for_merchandise_line`
(recoverable). The development store has no shipping rate for this product, so the store's own checkout may refuse to
go on at the delivery step. The agent's part is right either way: the message is on the handoff allowlist, because the
buyer gives the address at the store. But this run doesn't show a buyer able to pay. A store with a shipping rate (or a
product that needs no shipping) would.

## get_checkout against create_checkout

No drift. Across the agent's create, its GETs at 0 s and 60 s, and the probe's create, GETs and same-line update, every
read had currency USD, subtotal 69995 and total 69995, with no tax, shipping or discount.

| Read | Subtotal | Tax | Shipping | Discounts | Total |
| --- | --- | --- | --- | --- | --- |
| Agent create (stored summary) | — | — | — | — | 69995 |
| Agent GET, 0 s and 60 s | 69995 | none | none | none | 69995 |
| Probe create, GET 0 s and 60 s, update | 69995 | none | none | none | 69995 |

`handOffChanged` stays strict: the GET must show the approved lines, currency and total exactly. This store charged no
tax and had no shipping for the item, so nothing that usually drifts was present. A store that estimates tax from the
buyer IP or a default location, adds a shipping estimate, applies a discount after the create, or prices by market
could differ between create and GET. That would show as `hand_off_changed` re-quotes and, past the P8 cap,
`price_unstable`.

## The leak scan

`scripts/h5-leak-scan.sh`: **LEAK SCAN PASSED** over `notes/h5/stage3/evidence` and the recordings, and over
`develop..HEAD`. It was run twice, with `BUYER_IP` set to the owner's current address and to the first run's address.
Every check was 0: client secret, client id, buyer IP, the store domain with `/cart/c/`, an unredacted `?key=`,
`Shopify-Buyer-IP` with a value, and `Authorization: Bearer` with a value. On the branch, 130 `?key=`, 43
`Shopify-Buyer-IP` and 57 `Bearer` matches are test and doc fakes, reported as info only.

A shape check without secrets, over the evidence and recordings, found no unmasked `?key=`, no buyer IP header with a
value, no bearer token and no `shpat_` token. It also found one `/cart/c/<token>` URL in `intent.json`. The script
wrote the handoff URL there with only its key masked; it now cuts it to the host, and the saved file was cut the same
way before the scan.

## What is not covered

- N1: no `complete` at Shopify; the buyer pays at the store.
- Order reads (VERIFY 7).
- The per-user buyer IP: `BUYER_IP` is the owner's own address, `local` profile only (19).
- Shipping selection at a real handoff merchant: Shopify's answers always carry messages, and the orchestrator sends a
  selection only for an answer without any, so that path never runs there. Tests cover it only with the messages
  removed.

## The VERIFY answers

| # | Question | Answer |
| --- | --- | --- |
| 5 | `continue_url`'s form and lifetime; cancel of a handed-off checkout | `https://<store>/cart/c/<token>?key=<key>`, `<token>` being the checkout id's; `expires_at` is 30 days after the last read or write; cancel after the buyer opened the page answered HTTP 200, `canceled` |
| 8 | A business refusal over MCP | `result.isError: true`, with a UCP error envelope in `structuredContent` (`ucp.status: error`, `messages[]` with code and severity, no id); not a JSON-RPC error |
| 9 | The arguments of `get_checkout` and `update_checkout` | `get_checkout`: `{id}`. `update_checkout`: `{id, checkout: {line_items: [{id, item: {id}, quantity}]}}`, each line with its `CartLine` id; the recorded update's `meta` held `ucp-agent` and `idempotency-key`. The store accepted both |
| 10 | Whether the MCP endpoint differs between versions | Not tested in H5; still open |
