# ADR 0009: Mutation testing with PIT (H3)

Status: accepted (H3 P10; proposed in P1). The H3 plan (notes/h3/h3-plan.md, with the owner's answers of 2026-11-14 and 2026-11-15) is the working note.

Where it runs: `scripts/mutation-pass.sh <pass>` locally under `-Pmutation`, and `.github/workflows/mutation.yml` by hand (`workflow_dispatch` only, read-only token, no secrets, no cache, actions pinned by SHA, one job per pass). `docs/results/h3-mutation-report.md` is generated from PIT's XML and `docs/mutation/equivalents.md` by `dev.ucplab.eval.report.MutationReport`, which fails on a star survivor with no approved entry, an entry without approval, or a stale entry; the star classes are in `docs/mutation/star-classes.txt`.

## Context

A passing test suite says little if it cannot fail. Three bugs got past our offline tests: the in-memory `recordAttempt` stored an attempt
before its I4 check (M6a C2), the gateway hard-coded the lab's payment handler (H1 C3), and `Redactor` dropped the shipping part of a
checkout (H1 C8a). Mutation testing changes the code under test (a boundary, a condition, a return value, a call) and asks whether a test
fails. A mutant that no test notices is a place where the same kind of bug could live.

## Decision

- **PIT 1.30.2 with `pitest-junit5-plugin` 1.2.3**, in the root pom's `-Pmutation` profile. The profile binds nothing to any phase: it
  only configures `pitest-maven`, which is run by name. `./mvnw verify` does not use it, and a build without `-Pmutation` is unchanged
  (in the effective pom of a plain build pitest appears only in `pluginManagement`, as a version pin; the list of build plugins and the dependency tree are identical to the ones before the change, and no phase mentions pitest). The versions are properties in the root pom, pinned in
  `pluginManagement`; the junit5 plugin is a plugin dependency, because the Maven goal fails without it.
- **Signatures verified.** The `.jar.asc` of `pitest`, `pitest-maven`, `pitest-entry` (1.30.2) and `pitest-junit5-plugin` (1.2.3) from Maven
  Central were checked with `gpg --verify` against one key, `18CD 85CB C5C1 DB16 84C5 E59F A8E3 B39F 19E1 CCE7` (ed25519, created 2023-01-03,
  expires 2029-01-01, uid "Henry Coles <henry.coles@googlemail.com>"; Henry Coles is the developer named in the project's pom). All four:
  `GOODSIG`, `VALIDSIG` with that fingerprint. **How the key was trusted, honestly:** the fingerprint was read from two keyservers run
  by different operators (keyserver.ubuntu.com and keys.openpgp.org) and agreed; a third party (a Gradle verification keyring in
  sava-software/sava) lists the same key id. **The project does not publish the fingerprint on its own site or GitHub** (pitest.org has none;
  the maintainer's GitHub account has no GPG key uploaded; its release workflow signs with a secret key). So the trust anchor is the keyservers,
  not the project's own channel. The owner accepted this on the keyservers' word on 2026-11-14, for a test tool that never ships in the product. The check was made on 2026-11-14 and is repeated on any upgrade: **any PIT or plugin upgrade must be signed by key `18CD85CBC5C1DB1684C5E59FA8E3B39F19E1CCE7`, or the owner decides again.**
- **JUnit 6, stated plainly.** Neither PIT nor the junit5 plugin declares support for JUnit 6. The plugin is built against JUnit Platform 1.9.2
  and its README says it "will automatically work with JUnit platform 1.5.0 to 1.10.0-M1 (and probably above)". The pair works with our
  Jupiter 6.1.3 (the Maven plugin adds `junit-platform-launcher` 6.1.3 itself), shown by every run recorded below. It is a tested combination,
  not a supported one: any upgrade of PIT, the plugin or JUnit repeats the three-module check, and the profile can fall back to pinning JUnit
  for mutation runs only.
- **The seed is mandatory.** Under `-Pmutation`, `-Dproperty.tries=500 -Dproperty.seed=1` are passed to PIT's forked test JVMs through
  `jvmArgs`; with a random seed a kill would not be reproducible. Proof that they arrive: with `-Dmutation.property.tries=0` or
  `-Dmutation.property.seed=notalong` the property tests fail in PIT's baseline run (`property.tries must be at least 1`, a number-format
  error). `verify` keeps 10,000 schedules and its own seed handling. The final pass on `DefaultOrchestrator` is run once at 10,000.
- **Mutators:** `STRONGER` on star paths (the default of the profile), `-Dmutation.mutators=DEFAULTS` elsewhere. STRONGER is DEFAULTS plus
  the switch mutator and the "remove conditional, equal else" case (pitest.org/quickstart/mutators, read 2026-11-14).
- **Test selection:** all of the module's tests (`dev.ucplab.*`), because narrowing to the class's package reads as no coverage (the Stage 0
  trap: 72 of 154 approval mutants). Excluded: `dev.ucplab.eval.*` (servers and child JVMs, minutes per run, not on a star path) and
  `*ArchitectureTest` (class scans; they pass on any mutant). agent-persistence is run with `-Dmutation.targetTests='dev.ucplab.persistence.*'`
  because core's test-jar tests read paths relative to agent-core and fail there; `SqlCrashMatrixTest`, the one persistence test in a core package
  that also fails under PIT's per-test runs, is left out until P9 decides how to include it. A comma list given on the command line matched nothing:
  one glob per run.
- **No incremental history.** PIT 1.30.2 refuses history files without the commercial arcmutate history plugin, so the plan's option C is not
  available; each run pays the coverage phase again.

## The survivor rule

- **Zero survivors and zero no-coverage mutants on the star paths** (the list in the H3 plan, Q2, including `RecoveryWorker` and `RecoveryLoop`), except
  a mutant listed in `docs/mutation/equivalents.md`. A test written to kill a mutant must fail on it: its commit names the mutant (class, method,
  mutator) and the run shows it KILLED by that test.
- An equivalent mutant is recorded with its reason and the test that shows the behaviour is unchanged, and counts only with an `approved: owner <date>`
  line in that file, which a session adds only after the owner approves it in chat. Unreachable code is a finding, not an equivalent.
- **Elsewhere** every survivor is killed or listed with a reason. There is no score threshold: a threshold invites tests written to move a number.

## What it can and cannot prove

It proves that the tests fail when a guard, a boundary, a return value or a call on the mutated classes changes. It does not prove that the
invariants are the right ones, that equivalence claims are right (a person decides), or anything about code it does not mutate. **Not mutated:**
agent-service's owner scoping (covered by S18 and M6b's checks), the agent runtime and its tools, the faults the proxy injects, the evaluation harness,
the merchants. Concurrency interleavings stay with the seeded property tests. The SQL itself is not mutated either: PIT mutates Java bytecode, so the
migrations and the V7 trigger that stops `purchase_intent.merchant_id` from changing (I11) are not mutants; the trigger's own contract test
(`MigrationsTest.v7_aRawUpdateOfMerchantId_aborts_otherUpdatesDoNot` and `v7_upgradesAV6DatabaseWithRows_leavingEveryRowUnchanged`) covers it, and a
plant in the SQL (a trigger that lets the update through) is what that test is for.

## Cost (measured in P1, Java 21, Maven 3.10.0, 4 threads, 500 schedules)

| Run | Mutants | Killed | Survived | No coverage | Wall time |
|---|---|---|---|---|---|
| agent-core `RequotePolicy`, DEFAULTS | 12 | 11 | 1 | 0 | 103 s |
| agent-core `RequotePolicy`, STRONGER | 16 | 15 | 1 | 0 | 187 s |
| agent-ucp `FailureClassifier`, STRONGER | 109 | 98 | 10 | 1 | 30 s |
| agent-persistence `SqlPolicyUsage`, STRONGER (run twice) | 12 | 12 | 0 | 0 | 9 s and 11 s, identical results |
| agent-persistence `Sql*Repository`, STRONGER (run twice) | 461 | 431 (430 killed, 1 timed out) | 28 | 2 | 85 s and 85 s, identical results |
The fixed cost of a run is PIT's coverage pass over the module's tests: about 100 to 190 s in agent-core, about 25 s in agent-ucp. Mutants are
cheap after that. The `verify` build is unchanged in time.

The whole set, measured at the commit named in `docs/results/h3-mutation-report.md` (STRONGER, seed 1, one pass after another with `scripts/mutation-pass.sh`):
3354 mutants in 9 passes, about 31 minutes of wall time. Eight passes at 500 schedules took 80 to 246 s each (agent-ucp 80 s, agent-persistence 110 s,
agent-core by package 99 to 246 s); `core.orchestrator.*` at 10,000 schedules took 820 s for 662 mutants. The whole set is a manual workflow
(`mutation.yml`), not a CI gate.

## PIT is not a property-testing library

It mutates code and generates no inputs, so CLAUDE.md's rule against jqwik and other property-testing libraries is untouched.

## Supply chain

Apache 2.0 (the poms). Versions pinned in the root pom. Signatures verified as above. No CI job runs PIT yet (the manual workflow is P10: read-only token,
no secrets, actions pinned by SHA, `persist-credentials: false`, no shared cache). Proposed in the plan and not yet adopted: the report summary in `docs/results/`.
