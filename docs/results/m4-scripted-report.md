# Scripted evaluation report

- **Mode:** scripted, orchestrator-direct (M4, no model)
- **Commit:** `476662df2db1e3968739667fd68053a8cfd6a89f` on feature/m4-fault-proxy, from git
- **Generated:** 2026-11-12T20:57:36.893814Z
- **Runs per scenario:** 200
- **Time scale k:** 1
- **Lanes:** 16
- **Base seed:** 42
- **Runtime:** Java 21.0.13.0.2+1-LTS (Microsoft); Mac OS X 26.5.2 aarch64

## Scenarios

| ID | Fault | Expected | Runs | Terminal states | Expected outcome (Wilson 95%) | Violations | 95% upper bound | Time to terminal p50/p95 | Reconciliations, resolve p50/p95 | Model calls | Tokens | Cost |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| S0 | none | COMPLETED | 200 | COMPLETED 200 | 200/200 (0.9812–1.0) | 0 | 0.015 (rule_of_three) | 31/118 ms | 0, – | n/a (no agent until M5) | n/a (no agent until M5) | n/a (no agent until M5) |
| S1 | fail_before_forward {close} on POST /ucp/v1/checkout-sessions, times 2 | COMPLETED | 200 | COMPLETED 200 | 200/200 (0.9812–1.0) | 0 | 0.015 (rule_of_three) | 341/514 ms | 0, – | n/a (no agent until M5) | n/a (no agent until M5) | n/a (no agent until M5) |
| S2 | forward_then_drop_response on POST /ucp/v1/checkout-sessions/*/complete, nth 1 | COMPLETED | 200 | COMPLETED 200 | 200/200 (0.9812–1.0) | 0 | 0.015 (rule_of_three) | 267/311 ms | 200, 259/304 ms | n/a (no agent until M5) | n/a (no agent until M5) | n/a (no agent until M5) |
| S3 | forward_then_delay_response {1.5 x complete timeout} on POST /ucp/v1/checkout-sessions/*/complete, nth 1 | COMPLETED | 200 | COMPLETED 200 | 200/200 (0.9812–1.0) | 0 | 0.015 (rule_of_three) | 10278/10320 ms | 200, 261/303 ms | n/a (no agent until M5) | n/a (no agent until M5) | n/a (no agent until M5) |
| S4 | none; propose twice with the same basket (sequential in even runs, concurrent in odd runs) | COMPLETED | 200 | COMPLETED 200 | 200/200 (0.9812–1.0) | 0 | 0.015 (rule_of_three) | 10/17 ms | 0, – | n/a (no agent until M5) | n/a (no agent until M5) | n/a (no agent until M5) |
| S5a | POST /_lab/price item_123 2500 -> 2600 before the first decision; approve every quote | COMPLETED | 200 | COMPLETED 200 | 200/200 (0.9812–1.0) | 0 | 0.015 (rule_of_three) | 44/49 ms | 0, – | n/a (no agent until M5) | n/a (no agent until M5) | n/a (no agent until M5) |
| S5b | POST /_lab/price item_123 2500 -> 2600 before the first decision; decline the re-quote | REJECTED | 200 | REJECTED 200 | 200/200 (0.9812–1.0) | 0 | 0.015 (rule_of_three) | 42/48 ms | 0, – | n/a (no agent until M5) | n/a (no agent until M5) | n/a (no agent until M5) |
| S6 | POST /_lab/stock item_123 0 after the quote, before the first decision | FAILED_NOT_CHARGED | 200 | FAILED_NOT_CHARGED 200 | 200/200 (0.9812–1.0) | 0 | 0.015 (rule_of_three) | 11/15 ms | 0, – | n/a (no agent until M5) | n/a (no agent until M5) | n/a (no agent until M5) |
| S7 | none; the agent pays with the mock card the merchant declines (A.2) | FAILED_NOT_CHARGED | 200 | FAILED_NOT_CHARGED 200 | 200/200 (0.9812–1.0) | 0 | 0.015 (rule_of_three) | 11/15 ms | 0, – | n/a (no agent until M5) | n/a (no agent until M5) | n/a (no agent until M5) |
| S8 | fail_before_forward {status 503} on every method of /ucp/v1/**, installed just before the decision and cleared 20 s / k later through the admin API | COMPLETED or ESCALATED | 200 | COMPLETED 200 | 200/200 (0.9812–1.0) | 0 | 0.015 (rule_of_three) | 21422/22360 ms | 200, 21407/22346 ms | n/a (no agent until M5) | n/a (no agent until M5) | n/a (no agent until M5) |
| S9 | kill -9 of the agent child at K1 (complete held before forwarding), K2 (the merchant's answer held) or K3 (first read held after a dropped answer), in turn; restart on the same database with a new worker id | COMPLETED | 200 | COMPLETED 200 | 200/200 (0.9812–1.0) | 0 | 0.015 (rule_of_three) | 36190/36339 ms | 200, 327/30811 ms | n/a (no agent until M5) | n/a (no agent until M5) | n/a (no agent until M5) |
| S11 | none; the proposal names a merchant missing from the registry | REJECTED | 200 | REJECTED 200 | 200/200 (0.9812–1.0) | 0 | 0.015 (rule_of_three) | 1/1 ms | 0, – | n/a (no agent until M5) | n/a (no agent until M5) | n/a (no agent until M5) |
| S13 | duplicate_request on POST /ucp/v1/checkout-sessions/*/complete, nth 1 (parallel in even runs, sequential in odd runs) | COMPLETED | 200 | COMPLETED 200 | 200/200 (0.9812–1.0) | 0 | 0.015 (rule_of_three) | 3/5 ms | 0, – | n/a (no agent until M5) | n/a (no agent until M5) | n/a (no agent until M5) |
| S14 | none; 24 workers, each with its own orchestrator, worker id and connection, call advance at once; payment latency complete timeout / 4 | COMPLETED | 200 | COMPLETED 200 | 200/200 (0.9812–1.0) | 0 | 0.015 (rule_of_three) | 2536/2561 ms | 0, – | n/a (no agent until M5) | n/a (no agent until M5) | n/a (no agent until M5) |
| S28 | complete closed before forwarding (even runs) or answered and dropped (odd runs); 503 on every read; kill -9 once D - L/2 has passed since uncertain_since; restart on the same database with a new worker id | ESCALATED | 200 | ESCALATED 200 | 200/200 (0.9812–1.0) | 0 | 0.015 (rule_of_three) | 82284/83894 ms | 200, 76853/78424 ms | n/a (no agent until M5) | n/a (no agent until M5) | n/a (no agent until M5) |

## Invariants

| Invariant | Scenarios | Runs | Violations | 95% upper bound (method) |
|---|---|---|---|---|
| I1 | 15 | 3000 | 0 | 0.001 (rule_of_three) |
| I2 | 15 | 3000 | 0 | 0.001 (rule_of_three) |
| I3 | 15 | 3000 | 0 | 0.001 (rule_of_three) |
| I4 | 15 | 3000 | 0 | 0.001 (rule_of_three) |
| I5 | 15 | 3000 | 0 | 0.001 (rule_of_three) |
| I7 | 15 | 3000 | 0 | 0.001 (rule_of_three) |

## Failure analysis

No violations, unexpected outcomes or aborted runs.

## Method

- Scripted, orchestrator-direct (13.1 M4 note): each run calls Orchestrator.propose with the scenario's fixed basket; an automatic approver decides through the approval service; the recovery worker drives the intent. There is no model, so model calls, tokens, cost and traces read n/a: no agent until M5.
- Real time throughout, every duration divided by the time scale k = 1 (13.1); approval lifetime and sign-in age are not scaled.
- Each run gets a fresh database, gateway, circuit breakers, worker id and user, and the merchant is reset before it; runs in one lane are sequential, lanes share nothing.
- Policy usage is a harness stub with no earlier spend or proposals: every run has a user of its own, so there is nothing to count. A real PolicyUsage is out of scope for M4 (it belongs with agent-service in M6).
- Invariants are checked on ground truth the agent does not control: O (/_lab/orders), G (the merchant's own GET of each order's session), L (the fault proxy's log in front of the merchant, including Idempotency-Key and a keyed body hash) and A (the approver's own decisions). An aborted run fails every check.
- Terminal states, the expected outcome and reconciliation times come from the agent's stored state (D), which is what they describe; time to terminal is the harness's own observation.
- I1 (at most one order per run, and each order's session is completed with that order): O (/_lab/orders) and G (the merchant's GET of each order's session); D is not read.
- I2 (every order matches the snapshot the user approved for its key: merchant, session, lines, currency, and an amount not above the approved maximum): O, G (lines and currency of each order's session), L (which key produced the order, and when it was first sent) and A (the approved snapshot); D is not read.
- I3 (every complete follows an unexpired approval of the same session by the owner, matched by revision, with no decline in between): L (every complete, its key and arrival) and A (the decisions); D only for the stored approval row's status and decided_by, which I3 is about.
- I4 (after the first complete, no create for the intent and no request to another session, and the stored session id is the completed one): L (every request after the intent's first complete); D only for the final stored session_id, which I4 is about.
- I5 (every intent is terminal with no cancel owed, and no state outlasted its 7.6 bound): D for the end state and the time in each state (I5 is about the agent's state), L and the harness's own terminal observation for the last complete stretch.
- I7 (one key and one byte-identical body per approval, a new key for each new approval): L (keys and bodyHmac of every complete) and A (the approval in force); D only as a cross-check that L's body hash is the hash of the agent's frozen body.

## Configuration

```json
{
  "commit": {
    "sha": "476662df2db1e3968739667fd68053a8cfd6a89f",
    "branch": "feature/m4-fault-proxy",
    "dirty": false,
    "source": "git"
  },
  "config": {
    "runsPerScenario": 200,
    "timeScale": 1,
    "lanes": 16,
    "baseSeed": 42,
    "ucp": {
      "connectTimeoutMs": 2000,
      "requestTimeoutMs": 5000,
      "completeTimeoutMs": 10000,
      "maxRetries": 3,
      "backoffInitialMs": 200,
      "backoffCapMs": 2000,
      "breakerThreshold": 5,
      "breakerOpenForMs": 30000
    },
    "orchestrator": {
      "leaseMs": 30000,
      "completeTimeoutMs": 10000,
      "resendQuietPeriodMs": 20000,
      "reconcileDeadlineMs": 60000,
      "maxCompleteResends": 2,
      "pollInitialMs": 250,
      "pollMaxMs": 2000,
      "pollJitter": 0.2
    },
    "recovery": {
      "periodMs": 5000,
      "limit": 50,
      "maxStepsPerIntent": 500
    },
    "merchant": {
      "ucpVersion": "2026-08-25",
      "module": "dev.ucplab:merchant (in process, same commit)"
    },
    "runtime": ["Java 21.0.13.0.2+1-LTS (Microsoft)", "Mac OS X 26.5.2 aarch64"]
  }
}
```

Exit code 0: 0 runs with violations, 0 unexpected outcomes, 0 aborted.

## Deferred

- Model calls, tokens, cost per completed purchase and trace links: n/a (no agent until M5).
- The I8 canary grep across logs and traces: M5, with the agent runtime.
