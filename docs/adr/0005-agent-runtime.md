# ADR 0005: The agent runtime, its modules, budgets and trace, and live mode

Status: accepted (M5). m5-plan.md (with its Decisions block) set the shape; ADR 0004 records why the live client is
built on `google-genai` and not ADK. This records what M5 built, what was measured, and what it leaves for M6.

## Decision

- **Our loop owns every tool call** (6.3, I6, Decision 2). `AgentRuntime` calls `ModelClient.generate` once per turn,
  runs each tool call the turn asks for through `ToolRegistry`, and checks the budget before every model and tool call.
  No SDK runner, no automatic function calling. The five tools are the only `Tool` implementations (ArchUnit).
- **Module layout** (Decision 1):
  - `agent-core` holds the ports (`ModelClient`, `Conversation`, `Message`, `ToolSpec`, `ToolCall`, `ModelTurn`,
    `Usage`, `RunRecord`, `RunRepository`), JDK only.
  - `agent-runtime` (JDK and core only) holds the loop, the budgets, the five tools, the system prompt, `UntrustedText`,
    `ScriptedModel`, `RunTrace` and `ModelClients`.
  - `agent-adk` holds only the `dev.ucplab.adk.gemini` package (`GeminiModelClient`, its mapping, `GeminiProvider`),
    and is the only module that imports Gemini SDK types.
  - `eval-harness` depends on agent-runtime and never on agent-adk; the `eval-live` profile adds agent-adk at runtime
    scope and `ModelClients.load("gemini", key)` finds it through `ServiceLoader`.
- **`ModelKey`, `ModelClientProvider` and `ModelClientSettings` live in agent-core**, not in agent-adk as Decision 1
  listed. The harness must name the key type to hand it to `ModelClients.load` without depending on agent-adk, and
  agent-adk depends on core only, so core is the one module both see. The plan's "no getter outside the gemini package"
  is kept by rule rather than by visibility: `ModelKey.reveal()` is public, and every module's ArchUnit test forbids a
  call to it outside `dev.ucplab.adk.gemini`. `toString` is `ModelKey[redacted]`.
- **Budgets** (6.3 [CONTRACT], unchanged): 6 model calls, 8 tool calls, 30 s wall clock with human waiting excluded,
  20,000 input plus output tokens per run. Two choices the contract left open:
  - **Thinking tokens count as output tokens** (`candidatesTokenCount + thoughtsTokenCount`), toward the TOKENS
    budget, the report's tokens and its cost: genai reports them apart from the candidates, and the pricing page bills
    them at the output rate ("Output price (including thinking tokens)", read 2026-11-13).
  - **A response without usage metadata fails the call** (`MISSING_USAGE`) rather than running unmetered.
- **The per-call timeout is fixed at construction** (`ModelClientSettings.callTimeout`, at most 30 s), because 6.5's
  `generate(Conversation, List<ToolSpec>)` has no timeout parameter. A timeout taken from the *remaining* budget would
  need 6.5 changed (a parameter, or a budget handle on the conversation); that is raised for review, not done.
- **Thought signatures.** Gemini 3 and 4 refuse a request (400) whose current turn's function call parts lack the
  `thoughtSignature` they came with. 6.5's `Message.Model` carries none, so `GeminiModelClient` keeps each function call
  part as received, by call id, and sends it back unchanged. One client therefore serves one run's conversation, and the
  harness builds one live client per run.
- **A trace file per run, not OpenTelemetry** (Decision 5). `RunTrace` writes `trace.jsonl` with the section 11 span
  names and attributes (`agent.run`, `llm.call`, `tool.call`), never prompt, model or tool text. The M7 move to OTel
  keeps the same names.
- **`PriceTable` lives in eval-harness** (C13), not agent-adk: only the report prices anything. The price file
  (`eval-harness/src/main/resources/dev/ucplab/eval/prices/gemini-3.8-flash.json`) holds the model, the date it was
  read, the currency, the input and output price per million tokens, and its source URL.
- **The run record ends `failed` with a reason** (C9, stage 4 item 1): `model_error`, `tool_error`,
  `internal_error` or `script_drift`. A record always ends, even when the finish itself throws.
- **Live mode** (C16): `EvalMain --mode live` runs S10 and S12 under caps of $5 estimated cost and 2,000,000 tokens
  (defaults and maxima). At either cap no more runs start, the report is REDUCED with the reason, and the exit code is
  3. A live run whose record ends failed is an aborted run. The live I6 check reads the function names of each request
  the client built. At the end the output folder and the captured stdout and stderr are scanned for the key and the
  canary; a hit empties the folder.

## Measured

- **The full scripted report at the M5 head** (`docs/results/m5-scripted-report.md`, commit `0721be2`, k = 1, 200 runs
  per row, 16 lanes, `--require-full`): 22 rows, 4,400 runs, 0 violations, 0 unexpected outcomes, 0 aborted; every
  row 200 of 200 expected. A-S10's crafted script follows the injection in 200 of 200 runs and the careful approver
  rejects every one (I2 and I3 hold).
- **The time the agent rows add.** The evaluation ran from 13:58:05Z to 14:31:54Z on 2026-11-13: **33:49**, the same
  as M4's 33:49 for the 15 orchestrator-direct rows. The seven agent rows add no measurable wall time at 16 lanes. In
  isolation (stage 4), 20 runs of each agent row at k = 1 took 11.8 s of run time in all (A-S2's p50 289 ms the
  largest), about 118 s at 200 runs, or 7 to 8 s spread over 16 lanes, which disappears into the schedule set by the
  long real-time rows (S28's 82 s, S9's 36 s). m5-plan section 9's estimate of +2 to 4 min was an upper bound and is
  left as written.

## Open for M6

- **23.1's run status list has no `failed`**, although the runtime has recorded one since C9 (with a reason). M6 must
  decide: add `failed` to 23.1, or map it to another status (for example `done` with an error). 23.1 is not changed
  here.
- The per-call timeout from the remaining budget (above), if 6.5 is to change.
- `RunRecord` persistence: M5 keeps run records in memory (no `agent_run` migration, Decision 5).
- Carried from M4 and untouched by the runtime: the approval lease, `PolicyUsage`, the recovery loop's graceful
  shutdown and its fixed log reason.
- The S9 and S28 child JVMs do not receive the evaluation's canary; their logs are still searched.
