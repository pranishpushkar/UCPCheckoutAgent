# H1 interop report

| row | runs | terminal states | orders (sample's own table) | violations | time | agreement with the lab |
| --- | --- | --- | --- | --- | --- | --- |
| S0 | 20 | {COMPLETED=20} | 20 | 0 | 5.8 s | agrees |
| S1 | 20 | {COMPLETED=20} | 20 | 0 | 6.2 s | agrees |
| S2 | 20 | {COMPLETED=20} | 20 | 0 | 6.1 s | agrees |
| S3 | 20 | {COMPLETED=20} | 20 | 0 | 56.1 s | agrees |
| S4 | 20 | {COMPLETED=20} | 20 | 0 | 4.8 s | agrees |
| S5 | 20 | {COMPLETED=20} | 20 | 0 | 4.9 s | differs: no re-quote (complete-charges-held-quote) |
| S6 | 20 | {FAILED_NOT_CHARGED=20} | 0 | 0 | 4.8 s | agrees (refusal shape differs: refusal-4xx-at-complete) |
| S7 | 20 | {FAILED_NOT_CHARGED=20} | 0 | 0 | 4.8 s | agrees (refusal shape differs: refusal-4xx-at-complete) |
| S8 | 20 | {COMPLETED=20} | 20 | 0 | 109.8 s | agrees |
| S13 | 20 | {COMPLETED=20} | 20 | 0 | 4.8 s | agrees |
| S14 | 20 | {COMPLETED=20} | 20 | 0 | 6.0 s | agrees |

Totals: 220 runs, 180 orders, 0 violations.

## Differences observed

| id | class | status |
| --- | --- | --- |
| refusal-4xx-at-complete | choice | n/a |
| error-codes-upper-case | theirs | n/a |
| complete-charges-held-quote | ambiguity | n/a |
| fulfillment-mandatory | choice | n/a |
| endpoint-at-origin | theirs | n/a |
| profile-version-2026-04-08 | theirs | n/a |
| no-catalog-capability | theirs | n/a |
| redactor-dropped-fulfillment | ours | fixed in c7fab56 |
| payment-handler-constant | ours | fixed in 92f8b6e |
| agent-profile-fetch | theirs | n/a |
| idempotency-conflict-409 | theirs | n/a |
| required-headers-422 | theirs | n/a |
| session-protection-hides-key-bug | theirs | n/a |

## Probes

- S17 first complete: 200 completed; same key and same body: 200 completed; same key and a DIFFERENT body: 409 {"ucp":{"version":"2026-04-08","status":"error"},"messages":[{"type":"error","code":"IDEMPOTENCY_CONFLICT","content":"Idempotency key reused with different parameters","severity":"unrecoverable"}]}; orders created by the three: 1
- create without Request-Id: 422 {"ucp":{"version":"2026-04-08","status":"error"},"messages":[{"type":"error","code":"INVALID_REQUEST","content":"✖ Field required\n  → at request-id","severity":"unrecoverable"}]}
- create without UCP-Agent: 422 {"ucp":{"version":"2026-04-08","status":"error"},"messages":[{"type":"error","code":"INVALID_REQUEST","content":"✖ Field required\n  → at ucp-agent","severity":"unrecoverable"}]}
- create without Idempotency-Key: 422 {"ucp":{"version":"2026-04-08","status":"error"},"messages":[{"type":"error","code":"INVALID_REQUEST","content":"✖ Field required\n  → at idempotency-key","severity":"unrecoverable"}]}
- unreachable agent profile URL: the agent's run ended COMPLETED, new orders 1, violations []
