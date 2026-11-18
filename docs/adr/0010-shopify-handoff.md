# ADR 0010: A Shopify store over MCP, by handoff (H5)

Status: accepted (2026-11-18), after the Stage 3 live run ([h5-shopify-report.md](../results/h5-shopify-report.md)). The H5 plan, `docs/interop/shopify-spike.md` and `docs/interop/shopify-live.md` are the working notes.

## Decision

- **Handoff, not agent completion.** At a `completion: handoff` merchant the agent quotes, takes the person's approval,
  reads the session once more and hands the person the store's `continue_url` (7.2, `HANDED_OFF`). It never sends
  `complete` (N1). The store's profile names no payment handler the agent could hold (`payment_handlers: {}`, 8.4), and
  the buyer pays at the store's own checkout, where they see the final total.
- **An MCP gateway beside the REST one.** `McpMerchantGateway` speaks JSON-RPC `tools/call` to the endpoint the store's
  profile names in `ucp.services["dev.ucp.shopping"]` (8.4). It offers create, update, get and cancel; no complete over
  MCP (19). The REST gateway and the lab merchant are unchanged.
- **I12 in two places, and the stored merchant settings.** A mutating call goes only to a merchant the operator owns:
  checked at propose, in the orchestrator before the policy, and before every send, including each call's endpoint host
  against `shop_domain` (I12, 7.2, 8.4). The settings an intent was proposed under (owned, shop domain, transport,
  completion) are stored with it as its fingerprint (10), so a merchants file changed under an intent in flight is
  refused at startup, not silently obeyed (19).
- **The buyer IP.** The store refuses every call without `Shopify-Buyer-IP`. In H5 its value is the owner's own address,
  `BUYER_IP`, accepted in the `local` profile only and masked everywhere (8.4, 19, I8). The production path is the end
  user's address from their request, forwarded only to the merchant they buy from, kept with the intent until it is
  terminal and erased in the same write (19).
- **The access token in memory.** Client credentials come from the environment by `credential_ref: env:NAME` (locally
  from the macOS Keychain), are exchanged for an access token held in memory only, refreshed before it expires, and never
  logged (20, 26.4, I8).

## Rejected

- **Agent completion with the dev store's test gateway** (the `bogus` card brand): it would send `complete` and a payment
  credential to Shopify, which N1 rules out in H5.
- **Storing each user's IP now:** H5 has one buyer, the owner; a per-user IP is personal data with a retention rule (19)
  that H5 has no need to carry yet.
- **A TEST-NET address as the buyer IP:** the store would see an address that is no one's, and the run would no longer
  show what a real buyer's call looks like.

## Consequences

- A handoff intent ends at `HANDED_OFF` with no order the agent can read; whether the person paid is the store's record,
  not ours (VERIFY 7).
- `hand_off_changed` compares the session the store shows with the approved snapshot exactly (7.2, I2); a store that
  adds charges between quote and handoff costs a re-quote and, past the P8 cap, ends `price_unstable`. The live run
  measures whether Shopify does. In Stage 3 it did not: the session at handoff matched the approved snapshot exactly, with
  no re-quote (`requote_count` 0); that store charged no tax or shipping for the item, so nothing that usually drifts was
  present.
- At a handoff merchant the agent sends no buyer identity: the store asks the buyer at its own checkout, and refused the
  local sign-in's email (`buyer_identity_email_is_invalid`) in the second Stage 3 run.
