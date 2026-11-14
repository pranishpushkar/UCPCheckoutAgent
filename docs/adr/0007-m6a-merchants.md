# ADR 0007: Several merchants (M6a Stage 1), and what H1 changed in the gates

Status: draft (H1 C2). It records what M6a Stage 1 built (I11 in the repository, the orchestrator choosing a gateway by
merchant, the per-merchant gates) and the Decisions block of the M6a plan as built. Stage 2 onwards (registry, fan-out
search, the lab with three merchants) is added when those commits land. Three small H1 changes to the gates are in
"H1 C2".

## Decisions of the M6a plan, as built

1. **`--catalog` on the lab merchant** (option A). Added in M6a C5; the default catalog and the original 46 SelfTest
   checks are unchanged. Not yet built at this commit.
2. **Registry and failure rules.**
   - There is no `merchant` table in M6a; merchants come from `merchants.yaml`.
   - A merchant removed from the file while intents are in flight refuses startup, with a fixed message, through one
     system read beside `RecoveryScan`. (Not yet built.)
   - Endpoint hosts are an operator allowlist (`endpoint_hosts`). Built: `UcpHttpGateway` accepts the base URL's host
     and port and the listed ones, with the base scheme.
   - **One breaker and one rate limiter per merchant**, keyed by merchant id, not by host and port: two merchants on one
     host and port get two breakers (`CircuitBreakers.forMerchant`, `limiterFor`, `gateFor`; the limiter is asked first,
     so a refused token never uses the breaker's half-open probe). The reconciliation read bypasses both (7.5).
   - The re-check of a SUSPENDED merchant is on demand, at most every 30 s, and single-flight. (Not yet built.)
3. **Compose with three proxies**; ADR 0006 stays as history. (Not yet built.)
4. **The service builds one `CircuitBreakers`**, shared by catalog and checkout calls (a catalog failure opens the breaker
   checkout uses). Built in C4 (`SharedBreakerTest`, `StackBreakerTest`).

## What Stage 1 built

- **I11: an intent's merchant never changes.** The in-memory and SQL repositories refuse an update, `recordAttempt` or
  `resolveAttempt` that names another merchant (`IllegalArgumentException("I11: ...")`, before any write; SQL checks it
  before lease and version). `V7__intent_merchant_immutable.sql` adds a trigger so a raw `UPDATE` of `merchant_id`
  aborts. A plain SQL `CREATE TRIGGER` migration works under Flyway's SQLite parser.
- **The in-memory `recordAttempt` and I4.** It used to put the attempt before the I4 check and not count a COMPLETE
  attempt being recorded; it now computes the merged intent first, as SQL does, so an I4 refusal records nothing.
- **The orchestrator picks the gateway by merchant** (`Function<MerchantId, MerchantGateway>`; the single-gateway
  constructors wrap it). A merchant with no gateway throws the unchecked `NoGatewayException` before anything is
  written or sent, and never falls back to another merchant's gateway.

### A RECONCILING intent whose merchant has no gateway does not escalate after its deadline

`NoGatewayException` is thrown at the top of every step that calls a merchant, before the first write. In
`reconcile` the gateway is fetched first, and the escalation after the reconciliation deadline is only reached from
the result of the read (a failed read, or a COMPLETED, COMPLETE_IN_PROGRESS or similar view that stays unsettled past
the deadline). With no gateway there is no read, so escalation is not reached: the intent stays RECONCILING, nothing in its state, version, attempts or
events changes, and `RecoveryWorker` logs "advance failed" at ERROR on every pass. This is chosen on purpose:

- Moving it to a human state from here would write a state change on the strength of a wiring fault, not a fact about
  the order. A merchant with no gateway is a configuration error (startup refuses a registry that drops a merchant
  with intents in flight), not something the intent's own clock should resolve.
- An escalation without having read the merchant could hide an order that was charged; staying RECONCILING keeps the
  duty to look, and the intent resumes the moment the gateway is back.
- The cost is that such an intent is invisible to the owner until an operator reads the log. The ERROR line carries the
  merchant id so it can be found. A visible alternative (a metric or an operator listing) is future work.

The same holds for an owed cancel at such a merchant (`reconcilingAndOwedCancelAtAMerchantWithNoGateway_areLeftAlone`).

## H1 C2: three changes to the gates

- **A 429 does not clear the cached profile.** A create answered 4xx dropped the profile (section 20) because a 4xx can
  mean the profile changed. A 429 means the merchant is busy, so dropping the profile only made every retry fetch
  discovery as well. A 422 and the other 4xx still clear it.
- **`Gate.reason()`.** The limiter reports `rate_limited` and the breaker `circuit_open`; `Gates.all` reports the reason of
  the gate that refused this thread's last `tryAcquire`. `RetryExecutor` builds the `NotSent` cause from it
  (`FailureClassifier.refused`): "rate limited for host:port", or, as before, "circuit breaker open for host:port". M6a
  Stage 1 reported an empty bucket as an open circuit. Nothing was sent either way, and the outcome type is unchanged.
- **An https endpoint host written without a port means 443** in the operator's allowlist. An http host without a port
  is still refused (`an endpoint host is host:port`), because the port of a plain http merchant is not a safe default
  to guess.
