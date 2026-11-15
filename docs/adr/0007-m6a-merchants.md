# ADR 0007: Several merchants (M6a), and what H1 changed in the gates

Status: complete for M6a (C15). It records what M6a built: I11 in the repository, the orchestrator choosing a gateway by
merchant, the per-merchant gates (Stage 1); the lab merchant's `--catalog` option, the registry, the service wiring and
fan-out search (Stage 2); the console, the CLI and Compose with three merchants and three proxies (Stage 3); and the
harness's multi-merchant lane with rows S20, S21 and S22 (Stage 4). Three small H1 changes to the gates are in "H1 C2".

## Decisions of the M6a plan, as built

1. **`--catalog` on the lab merchant** (option A). Added in M6a C5; the default catalog and the original 46 SelfTest
   checks are unchanged. Built in C5.
2. **Registry and failure rules.**
   - There is no `merchant` table in M6a; merchants come from `merchants.yaml`.
   - A merchant removed from the file while intents are in flight refuses startup, with a fixed message, through one
     system read beside `RecoveryScan` (`InFlightMerchants`). Built in C7.
   - Endpoint hosts are an operator allowlist (`endpoint_hosts`). Built: `UcpHttpGateway` accepts the base URL's host
     and port and the listed ones, with the base scheme.
   - **One breaker and one rate limiter per merchant**, keyed by merchant id, not by host and port: two merchants on one
     host and port get two breakers (`CircuitBreakers.forMerchant`, `limiterFor`, `gateFor`; the limiter is asked first,
     so a refused token never uses the breaker's half-open probe). The reconciliation read bypasses both (7.5).
   - The re-check of a SUSPENDED merchant is on demand, at most every 30 s, and single-flight. Built in C6.
3. **Compose with three proxies**; ADR 0006 stays as history. Built in C10; see "The rename" below.
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

## What Stages 2 to 4 built

### The lab merchant's `--catalog` option (C5)

`MerchantServer.main(port, --catalog file)` serves the items of a JSON file (`CatalogFile`: ids `[A-Za-z0-9_-]{1,100}`,
non-empty titles, price at least 1, stock at least 0, at most 50 items, ids unique); `reset` restores that list; an
unreadable or invalid file stops start-up with a fixed message. `new Catalog()` equals the built-in three items item by
item, and no line of the original 46 SelfTest checks changed. It is the one change to `merchant/src` since M2; CLAUDE.md
names it. `dev/catalogs/b.json` and `c.json` have their own ids (`b_*`, `c_*`), and `b_cap_03` has stock 1 for S21. Merchant
A has no file. The alternative (the built-in catalog everywhere, differences through `/_lab`) would have made `get_product`
ambiguous and lost the differences at the first lab reset.

### The registry (C6)

`merchants.yaml` (`MERCHANTS_FILE`) lists the merchants; `ConfiguredMerchantRegistry` is the `MerchantRegistry`. A merchant is
ACTIVE when its file status is `active` and its last discovery check passed; otherwise it is SUSPENDED with a fixed reason
(`unreachable`, `incompatible_version`, `capability_missing`, `endpoint_not_allowed`, `handler_missing`, `invalid_profile`,
`suspended_in_file`) and is left out of `active()`, so P1 denies it. A failing merchant never stops start-up. A SUSPENDED one
is re-checked on demand, at most every 30 s, single-flight: one caller wins a compare-and-set and the check runs on a virtual
thread, every other caller (the winner too) gets the last known status, and no lock is held across the network. The
registry caches nothing but a status; the profile cache is still the gateway's (section 20). Without `MERCHANTS_FILE` the
registry is the one entry of `MERCHANT_URL`, as in M6. `transport` and `completion` are read and validated (`rest` and `agent`
only) and never act: they leave room for M6c. There is no `merchant` table: the file is the registry.

Both classes (`ConfiguredMerchantRegistry`, `MerchantSettings`) live in `agent-ucp`, package `dev.ucplab.ucp.registry`. They
were written in `agent-service` and moved there in C11 (a pure move, with their tests): the harness runs the real registry,
and its `ArchitectureTest` forbids the service layer and Spring.

### The gateway seam (C3, C7)

`DefaultOrchestrator` takes `Function<MerchantId, MerchantGateway>` and `Function<MerchantId, PaymentInstrument>`
(`MerchantGateways.of(map)`, `MerchantInstruments.of(map)`); every step that calls a merchant uses the gateway of the
intent's merchant, and a merchant with no gateway is an unchecked `NoGatewayException`, never a fallback to another. The
map holds every configured merchant, SUSPENDED ones included, so an approved intent at a merchant that fails its check still
reconciles. The service builds one `CircuitBreakers` (and so one limiter per merchant) for the gateways and the catalog
clients. A new system read, `InFlightMerchants`, makes start-up refuse a `merchants.yaml` that drops a merchant with intents
in flight. An M6c transport is another function value, not a rewrite.

### Search over several merchants (C8, C8b)

`search_catalog` asks every active merchant at once on virtual threads, with one limit of 2 s for all of them, merges
round-robin in registry order whatever the answer order, and adds `unavailable: [merchant ids]` when at least one asked
merchant timed out or failed (DESIGN 6.2; absent when none failed, so a single merchant's output is unchanged byte for
byte). A cancelled call ends once and counts once against the breaker. `get_product` asks the active merchants the same
way and returns the item of the lowest registry index that has it; its output and error codes are unchanged (decision 5).
`propose_purchase` still takes `merchant_id` explicitly, so nothing here chooses where a purchase goes.

### The rename

`fault-proxy` is `fault-proxy-a` in `compose.yaml` and `scripts/compose-spike.sh`: three merchants need three proxies (a proxy
has one upstream), named `fault-proxy-a` to `-c`. ADR 0006 and the m6 progress notes keep the old name; they are history.
The merchant image is reused for merchants B and C (the catalogs and `merchants.yaml` are bind mounts), so the image scan
covers the same three images. `scripts/lab-recover.sh` restarts every dependent of `lab-net` after a `docker kill lab-net`.

### The harness (C11 to C14)

A lane may hold up to three merchants, each with its own proxy; a lane of one is what every earlier row uses and its report
is unchanged. `AgentStack` takes a list of merchants (one gateway and one catalog client each, over one `CircuitBreakers`);
over several merchants the registry is the real one, over one it is still the one-entry registry, so a one-merchant wire
(and H1's interop wiring) is exactly what it was. Session and order ids are each merchant's own (every merchant's counters
start at `chk_1001` and `ord_5001`), so a run over several merchants carries one view per merchant (its own orders, sessions,
proxy log and breaker state) and the invariant checks run on one slice per merchant. A row over several merchants may carry a
claim about the run as a whole, which aborts a run that does not bear it out. I3 now compares an approval with the owner of
its own intent, which must be one of the run's users (S21 has two).

- **S20** (`A-S20`): merchant B's catalog hangs on its first call and answers 503 after; the first search must return within
  twice the per-merchant limit and name B in `unavailable`, B's breaker must be OPEN and A's and C's CLOSED, and a purchase at
  A completes with one order and nothing but catalog reads sent to B.
- **S21** (`S21`): two users propose the one Wool Cap at B, both approve, both first completes are released through a latch on
  a seeded schedule while B is slowed; B ends with one order, one intent COMPLETED and the other FAILED_NOT_CHARGED. Oversell
  is prevented at the merchant (stock is taken under a lock before the charge); the agent reserves nothing.
- **S22** (`A-S22`): the agent proposes at A, then at B; the approver approves the first and rejects the second, after
  presenting the first intent's token for it, which must be refused; one order, at A, none at B.

Each row was shown failing against planted bugs: no per-merchant limit, one shared breaker, a missing `unavailable` report
(S20); a merchant whose stock never runs out (S21; the lab merchant cannot be stubbed, so its stock control stands in); an
approval service that accepts any token, and a decision that reads another intent's approval (S22).

## Known gaps

- The live S22 row (does a real model split a basket unprompted) has not been run.
- There is no `merchant` table, no merchant admin API, and the registry is read at start-up only.
- `get_product` has no `unavailable` field (6.2 gives it to `search_catalog` only).
- Entries 46 and 47 of `docs/mutation/equivalents.md` cite line numbers that moved when `UcpHttpGateway` changed in C6.
