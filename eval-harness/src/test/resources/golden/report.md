**REDUCED: 3 runs per scenario, time scale 4 — not an acceptance report**

# Scripted evaluation report

- **Mode:** scripted, orchestrator-direct (M4, no model)
- **Commit:** `0123456789abcdef0123456789abcdef01234567` on feature/m4-fault-proxy, from git
- **Generated:** 2026-11-12T12:00:00Z
- **Runs per scenario:** 3
- **Time scale k:** 4
- **Lanes:** 2
- **Base seed:** 42
- **Runtime:** Java 21.0.5 (Test); TestOS 1.0 x86_64

## Scenarios

| ID | Fault | Expected | Runs | Terminal states | Expected outcome (Wilson 95%) | Violations | 95% upper bound | Time to terminal p50/p95 | Reconciliations, resolve p50/p95 | Model calls | Tokens | Cost |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| S0 | none | COMPLETED | 3 | COMPLETED 3 | 3/3 (0.4385–1.0) | 1 | 0.7923 (wilson) | 120/140 ms | 1, 60/60 ms | n/a (no agent until M5) | n/a (no agent until M5) | n/a (no agent until M5) |
| S4 | none; propose twice with the same basket (sequential in even runs, concurrent in odd runs) | COMPLETED | 3 | COMPLETED 2, aborted 1 | 2/3 (0.2077–0.9385) | 1 | 0.7923 (wilson) | 110/130 ms | 0, – | n/a (no agent until M5) | n/a (no agent until M5) | n/a (no agent until M5) |
| S11 | none; the proposal names a merchant missing from the registry | REJECTED | 3 | COMPLETED 1, REJECTED 2 | 2/3 (0.2077–0.9385) | 0 | 1.0 (rule_of_three) | 25/90 ms | 0, – | n/a (no agent until M5) | n/a (no agent until M5) | n/a (no agent until M5) |

## Invariants

| Invariant | Scenarios | Runs | Violations | 95% upper bound (method) |
|---|---|---|---|---|
| I1 | 3 | 9 | 2 | 0.5474 (wilson) |
| I2 | 3 | 9 | 1 | 0.435 (wilson) |
| I3 | 3 | 9 | 1 | 0.435 (wilson) |
| I4 | 3 | 9 | 1 | 0.435 (wilson) |
| I5 | 3 | 9 | 1 | 0.435 (wilson) |
| I7 | 3 | 9 | 1 | 0.435 (wilson) |

## Failure analysis

### S0 run 2: violation of I1

- Seed 44, lane 0
- the merchant holds 2 orders for the run: [ord_1 on cs_1, ord_2 on cs_1]
- Evidence: `evidence/S0/run-2`

### S4 run 1: aborted

- Seed 20043, lane 1
- no terminal state within the time limit PT23.4375S
- Evidence: `evidence/S4/run-1`

### S11 run 1: unexpected

- Seed 220043, lane 1
- ended COMPLETED with 1 intent(s); expected [REJECTED]
- Evidence: `evidence/S11/run-1`

## Method

- Scripted, orchestrator-direct (13.1 M4 note): each run calls Orchestrator.propose with the scenario's fixed basket; an automatic approver decides through the approval service; the recovery worker drives the intent. There is no model, so model calls, tokens, cost and traces read n/a: no agent until M5.
- Real time throughout, every duration divided by the time scale k = 4 (13.1); approval lifetime and sign-in age are not scaled.
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
    "sha": "0123456789abcdef0123456789abcdef01234567",
    "branch": "feature/m4-fault-proxy",
    "dirty": false,
    "source": "git"
  },
  "config": {
    "runsPerScenario": 3,
    "timeScale": 4,
    "lanes": 2,
    "baseSeed": 42,
    "ucp": {
      "connectTimeoutMs": 500,
      "requestTimeoutMs": 1250,
      "completeTimeoutMs": 2500,
      "maxRetries": 3,
      "backoffInitialMs": 50,
      "backoffCapMs": 500,
      "breakerThreshold": 5,
      "breakerOpenForMs": 7500
    },
    "orchestrator": {
      "leaseMs": 10000,
      "completeTimeoutMs": 2500,
      "resendQuietPeriodMs": 5000,
      "reconcileDeadlineMs": 15000,
      "maxCompleteResends": 2,
      "pollInitialMs": 62,
      "pollMaxMs": 500,
      "pollJitter": 0.2
    },
    "recovery": {
      "periodMs": 1250,
      "limit": 50,
      "maxStepsPerIntent": 500
    },
    "merchant": {
      "ucpVersion": "2026-08-25",
      "module": "dev.ucplab:merchant (in process, same commit)"
    },
    "runtime": ["Java 21.0.5 (Test)", "TestOS 1.0 x86_64"]
  }
}
```

Exit code 1: 2 runs with violations, 1 unexpected outcomes, 1 aborted.

## Deferred

- Model calls, tokens, cost per completed purchase and trace links: n/a (no agent until M5).
- The I8 canary grep across logs and traces: M5, with the agent runtime.
