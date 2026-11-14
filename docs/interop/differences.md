# Differences between the lab merchant and the official sample (H1 C9)

Every difference the interop run observes is listed here with a class, or `-Pinterop` fails (`UNCLASSIFIED`). Classes:
`ours` (a bug of the agent: fixed, or accepted with a written reason), `theirs` (the merchant's own behaviour, legal or not),
`choice` (both shapes are legal and the agent accepts both, or a capability we chose to support or not), `ambiguity` (the
specification does not settle it). The status of an `ours` entry is `fixed in <commit>` or `accepted: <reason>`.

| id | class | why | status |
| --- | --- | --- | --- |
| refusal-4xx-at-complete | choice | The sample answers a refused complete (409 OUT_OF_STOCK, 402 INSUFFICIENT_FUNDS) as a 4xx with a UCP error envelope; the lab answers 200 with a message. Both shapes are legal under the checkout capability and the agent accepts both: the gateway reports either as the same refusal. | n/a |
| error-codes-upper-case | theirs | The sample writes some codes in upper case (OUT_OF_STOCK, INSUFFICIENT_FUNDS, RESOURCE_NOT_FOUND, VERSION_UNSUPPORTED); the lab uses lower case. The agent reads them case-insensitively. | n/a |
| complete-charges-held-quote | ambiguity | After a price change in the sample's products table, a GET does not show it and complete charges the quote it holds; only an update re-prices. Neither the 2026-04-08 nor the 2026-08-25 text of Complete Checkout says whether a business must re-price (read 2026-11-14), so S5 expects COMPLETED at the approved total and S16 cannot happen. | n/a |
| fulfillment-mandatory | choice | The sample needs a shipping method, destination and option (three full-replacement updates) before ready_for_complete; the lab needs none. The agent supports this through a FulfillmentPolicy (H1 C6) and sends no address. | n/a |
| endpoint-at-origin | theirs | The sample serves its REST endpoint at the origin (no /ucp/v1 prefix); the proxy rule paths follow the profile's endpoint (EndpointPaths). | n/a |
| profile-version-2026-04-08 | theirs | The sample's profile declares 2026-04-08 and refuses version="2026-08-25" with 422. The agent pins ucp_versions per merchant and sends the negotiated version. | n/a |
| no-catalog-capability | theirs | The sample declares no catalog capability, so the rows are orchestrator-direct and the agent cannot search it. | n/a |
| agent-profile-fetch | theirs | The sample fetches the URL in UCP-Agent on every create and update (4 times per run); the harness serves the profile on loopback. A run with the profile unreachable still completes (probe). | n/a |
| idempotency-conflict-409 | theirs | The sample answers the same key with a different body with 409 IDEMPOTENCY_CONFLICT and replays the same key and body with 200; both are correct behaviour and not a finding against the agent (S17 probe). | n/a |
| required-headers-422 | theirs | The sample answers a create without Request-Id, UCP-Agent or Idempotency-Key with 422 INVALID_REQUEST; the lab answers 400. | n/a |
| session-protection-hides-key-bug | theirs | A complete sent under a fresh key for an already completed checkout makes no second order at the sample (it protects the session), so the S13 planted bug "fresh key per complete" does not fail against it; the strict lab merchant does catch it. The sample cannot detect that class of bug. | n/a |
| redactor-dropped-fulfillment | ours | The gateway's scrubbing rebuilt the checkout view without its fulfillment part, so the selection never progressed (found by the first live row). | fixed in c7fab56 |
| payment-handler-constant | ours | UcpHttpGateway required the lab's handler id as a constant; the sample has another. | fixed in 92f8b6e |
