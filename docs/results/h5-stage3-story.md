DRAFT

# H5 Stage 3: three live runs to the first handoff

H5 ended with one purchase at the owner's Shopify development store reaching `HANDED_OFF`: the store quoted, the person
approved, and the buyer was handed the store's own checkout. It took three live runs on 2026-11-17. This is how the first
two failed, what they exposed, and what changed. The result itself is in [h5-shopify-report.md](h5-shopify-report.md).

Every run used `scripts/shopify-live.sh` against the owner's store over MCP, at UCP 2026-04-08, with the scripted model.
No `complete` was ever sent to Shopify (N1).

## Run 1: a failure with nothing to read

The run ended `FAILED_NOT_CHARGED` with the reason `quote_retries_exhausted`, and no approval was ever opened. The search
at the store had worked.

There was no way to say why:

- The script deleted its temporary directory on exit, and with it the database that held the attempt rows and events.
- The service log held no merchant calls, because the service never logs them (I8).
- The recordings were empty: the script had stopped before its own first call to the store.

What was left was a list of every path that ends in `quote_retries_exhausted`, and a best guess. Auth and the buyer IP
were unlikely, because the search had worked with the same token, IP header and endpoint, and a refusal for either comes
back as 401 or 422, which ends with a different reason. The leading suspect was a tool error from Shopify on
`create_checkout` (`isError: true`), which the transport did not read, so the gateway saw an uncertain answer and tried
it four times. The `propose_purchase` call took 3.3 s, which fits four tries with backoff.

The agent's create differed from the spike's working one in three ways:

1. it sent `buyer.email`, the local test user's address;
2. it sent no currency;
3. it put an idempotency key in the create's `meta`.

The first was the leading suspect.

## Keeping the evidence

Before running again, the script was changed so a failure would explain itself:

- **The evidence is kept** (`04346d9`). On every exit, failure included, the script saves the intent, its events and its
  attempts before the database is deleted, sanitized and redacted. Each attempt carries its request body, status, HTTP
  status and stored outcome.
- **A quote that opens no approval is replayed.** The service keeps only a summary of the store's answer, so the script
  resends the agent's last create with the agent's own idempotency key and body, and records the store's raw answer. Any
  checkout that answer names is cancelled.
- **Owed cancels are drained** (`b2ddf32`). On exit the script waits up to 30 s for the run's owed cancels to clear
  before it stops the service, and names any checkout still owed.

## Run 2: `buyer_identity_email_is_invalid`

The second run failed the same way, but this time the evidence said why. Shopify refused `create_checkout` as an MCP tool
error with a UCP error envelope:

- `ucp.status: error`;
- one message: code `buyer_identity_email_is_invalid`, severity `unrecoverable`, path `$.buyer.email`;
- no checkout id, status or totals.

The store would not take the local sign-in's address as a buyer email. The suspect was confirmed, and the other two
differences were cleared. The refusal also answered VERIFY 8: Shopify refuses with `isError: true` and an error
envelope, not with a JSON-RPC error.

Reading the attempts showed that one refusal had done much more damage than it should have. It exposed three bugs.

### Bug 1: a refusal was treated as an uncertain answer

The MCP transport discarded any answer marked `isError`, so a clear "no" from the store looked like an unreadable
answer. For a create, an unreadable answer is uncertain: the store might have made a checkout. So the gateway retried it,
and the create made four HTTP attempts, all refused the same way.

### Bug 2: the circuit breaker opened on a store that was answering

The breaker opens after five consecutive failures, and it counted an answer it could not read as a failure, even though
the store had replied. The four create attempts were four failures. After them, the orphan probe re-sent the create to
learn whether a checkout existed, and its first attempt was the fifth failure in a row. The breaker opened for the store.
The remaining probes were then refused by the open breaker, and used up the probe's budget in 5 ms.

The answer named no checkout id, so the store had made nothing and there was nothing to cancel. But the store had
answered every single call, and the agent had cut itself off from it.

### Bug 3: `quote_retries_exhausted` said the wrong thing

The run ended `quote_retries_exhausted`, which reads as "the store could not be reached". The store had been reached
every time, and had given a definite answer. The same reason also covered a readable quote that the handoff check
rejected, which is not a retry problem either.

## The fixes

The smallest fix came first and alone, so the third run would test it:

1. **No buyer identity at a handoff merchant** (`0b11bc7`). No create or update sends `buyer` at a handoff merchant: the
   buyer gives their details at the store's own checkout. The orphan probe rebuilds the create by the same rule, so its
   body stays byte-identical to the stored one. Agent merchants still receive the email.

The three bugs were fixed after the third run, one commit each, every one with red tests first:

2. **A refusal is a definite answer** (`ded75dc`). An MCP tool error that carries a UCP error envelope is passed through
   and read as a rejection. A refused create ends `create_rejected` with its codes, and sends no probe, because no
   checkout exists. A refused update ends `update_rejected`, and its checkout is cancelled. Neither is retried.
3. **An answer from the store is never a breaker failure** (`8718551`). An attempt the store answered below 500 resets
   the failure count, even when its body cannot be read. Only no reply, a timeout or a 5xx counts. A probe the breaker
   refuses no longer counts toward the three, and while the breaker is open a probe step writes nothing and sends
   nothing. Probing ends at a 10-minute deadline, so an outage cannot keep it going (`b2fdb21`).
4. **Unusable answers get their own reason** (`20f34c5`). At a handoff merchant, a readable quote the handoff check
   rejects ends `quote_unsupported`, and an answer with more than one shipping method or group ends
   `fulfillment_unsupported`. `quote_retries_exhausted` now means only that the gateway's retries ran out.

## Run 3: the first handoff

With only fix 1 in place, the third run went through:

| Step | Result |
| --- | --- |
| Search at the store | ok |
| Quote (`create_checkout`) | status `incomplete`, total 699.95 USD |
| Approval | one card, approved |
| Read again at handoff | the same as approved: no re-quote |
| Final state | `HANDED_OFF` |
| The store's cart page | the approved snowboard at the approved price |
| Cancel after the owner saw the page | HTTP 200, status `canceled` |

Without an email, the store asked for a contact method instead, and that message is one the handoff check already
allows. The run also answered VERIFY 5: the form of `continue_url`, a 30-day expiry that moves with every read or write,
and that cancel works on a handed-off checkout.

Two smaller things the run showed:

- **A false warning.** The script warned that a probe checkout might still be open after it had cancelled it. Fixed in
  `2e526f7`.
- **A cart token in the evidence.** The saved intent kept the handoff URL with its cart token. It is now cut to its host
  before it is saved, and the saved file was cut the same way.

The leak scan then passed over the run's evidence and recordings and over the branch: every check was 0.

## What it taught

- **Keep the evidence before you need it.** The first failure cost a whole run because the only record of it was
  deleted on exit.
- **A definite "no" is not an uncertain answer.** Treating a refusal as uncertain turned one answer into five attempts
  and a probe, and then into an open breaker.
- **A breaker should count silence, not answers.** A store that answers is alive, whatever its answer says.
- **A failure reason should say what happened.** `quote_retries_exhausted` hid a definite refusal behind "the store
  could not be reached".

The run did not show a buyer able to pay: the development store has no shipping rate for the item, so every answer
carried `delivery_no_delivery_available_for_merchandise_line`. That is the store's setting, not the agent's part. It is
listed in the report as not covered.
