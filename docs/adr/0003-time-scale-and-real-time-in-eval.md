# ADR 0003: Real time, a time scale and child JVMs in the scripted evaluation

Status: accepted (M4). Section 13 of the design and m4-plan sections 3, 4 and 8 set the shape; this records what the
harness does, what was measured and what it leaves for M6.

## Decision

- **Real time throughout.** The harness drives the real agent stack, the real fault proxy and the lab merchant over
  loopback HTTP, with `Clock.systemUTC()` and real sleeps. No fake clock: the races the scenarios exist to test (a
  complete that outlives its timeout, an outage that ends before the reconcile deadline, a takeover after a kill) are
  races between real sockets, threads and processes, and a fake clock would script them away.
- **One factor k divides every duration** (`TimeScale`). k buys speed at the cost of headroom, so a `TimeScale` is
  refused unless every ordering the design depends on holds, and every race a scenario relies on keeps at least
  `MIN_MARGIN` = 1 s of slack. The lease is not scaled linearly: 7.6 needs `L >= 2 Tc + 5 s`, so it is
  `max(30 s / k, 2 Tc + 5 s)`, which caps k at 5 (the S9 ordering breaks at 6). At k = 1: Tc 10 s, lease 30 s, period
  5 s, D 60 s. At k = 4: Tc 2.5 s, lease 10 s, period 1.25 s, D 15 s, poll max 0.5 s, S8 outage 5 s.
- **k = 1 for acceptance, k = 4 in verify.** `./mvnw verify` runs the reduced evaluation (N = 20, S9 6, S28 2, k = 4, 8
  lanes; `ScriptedEvaluationSmokeTest`), whose report says REDUCED on its first line. The acceptance report is
  `-Peval-full`: 200 runs per row, k = 1, 16 lanes, `--require-full`. Only that one goes in `docs/results/`.
- **S9 and S28 kill a real child JVM with kill -9** (`ChildProcess`, `Process.destroyForcibly`, exit 137), then start
  a new one on the same database file with a new UUIDv7 worker id. **At most 16 children at once**
  (`ChildProcess.MAX_CHILDREN`), one per lane of eval-full: a child peaked at about 91 MB RSS, so 16 are about 1.5 GB,
  and the simulated eval-full is about 34 min at 16 against about 57 min at 8.
- **The parent proposes and decides, the child works.** In S9 and S28 the harness process proposes the intent and
  approves it, and the child, a recovery worker on the run's file, takes the APPROVED intent on its next pass and sends
  the complete. m4-plan section 8 had the child do everything; the split puts the kill on the complete path, where the
  scenarios need it, and matches M6, where the REST API decides in its own process and the workers drive.
- **A decision in S9, S14 and S28 holds the intent for 1 ms** (`AgentStack.DECISION_ONLY`) rather than the
  orchestrator's lease, so another process can claim the APPROVED intent at once, as it must once the decider and the
  worker are different processes (below).

## Measured (macOS only)

Every timing here is from one Mac (Apple M5, 16 GB, macOS 26.5.2), on AC power. Linux, the CI platform, has not been
measured.

| Item | Result |
| --- | --- |
| Medians at k = 1 | S3 15.05 s, S8 20.63 s, S9 36.5 to 36.6 s by kill point, S14 2.52 s, S28 82.61 s; every other row 0.32 s or less |
| Medians at k = 4 | S8 5.37 s, S9 12.6 s, S14 0.65 s, S28 22.8 s |
| Smallest S3 margin in the reduced run (configured 1 s) | 1150 to 1167 ms in every smoke run the Mac did not sleep through |
| eval-full wall time | 33 min 49 s for 3000 runs on 16 lanes (the simulation said about 34 min); 39 min 44 s for the whole `verify -Peval-full`; 0 violations, 0 unexpected, 0 aborted (`docs/results/m4-scripted-report.md`, commit 476662d) |
| Child RSS | about 91 MB peak |

**macOS idle sleep breaks real-time runs.** While macOS idles into sleep, `System.nanoTime` stops but the wall clock
does not, so every `Instant` deadline and lease jumps on wake. One smoke run failed that way (S3 margin -77 s, I5
violations, S9 and S28 aborted at the time limit), at the same minute as an "Idle Sleep" entry in `pmset -g log`.
`caffeinate -i` was not always enough; a later verify slept for 16 min under it. eval-full was run under
`caffeinate -dims`, which holds a `PreventSystemSleep` assertion on AC power. No check or bound was changed.

## Consequences

- The reduced run's S3 margin is tight: about 160 ms above the 1 s floor at k = 4. A slower or busier machine could
  fail it; the floor stays, and k = 3 is the fallback if CI shows it.
- 137 as the exit value after kill -9, and the child classpath from `java.class.path`, are asserted on macOS only and
  carry TODO(VERIFY) until the first Linux CI run.
- **Open, owed in M6: the approval lease.** `DefaultApprovalService.decide` claims the intent under the deciding
  worker's id and holds it for the full orchestrator lease, so that the same process's orchestrator can send the first
  complete at once. That works only when the decider and the worker are one process. Once the REST API decides in its
  own process (M6), no worker could claim the APPROVED intent for a whole lease, 30 s at k = 1, before the first
  complete went out. The harness's 1 ms lease in S9, S14 and S28 models that split. The production default must change
  in M6 (a decision lease that covers only the decision's own write, or a hand-off to the worker); agent-core is not
  changed in M4.
