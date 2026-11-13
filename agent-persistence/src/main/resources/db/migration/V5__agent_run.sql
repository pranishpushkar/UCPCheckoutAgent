-- Section 10 agent_run and its children (M6, m6-plan section 5). Portable SQL, see V1; times are fixed-width text.
-- A run row holds names, counts, codes and times only: never a prompt, a message, model text, an ask_user question or
-- answer, tool arguments or results, or an exception message (I8, 23.1). The schema has no column that could hold one.

CREATE TABLE agent_run (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  model TEXT NOT NULL,
  scenario TEXT,
  started_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  ended_at TEXT,
  outcome TEXT CHECK (outcome IN ('done','waiting_for_approval','budget_exceeded','user_timeout','failed')),
  reason TEXT,
  model_calls INTEGER NOT NULL DEFAULT 0 CHECK (model_calls >= 0),
  tool_calls INTEGER NOT NULL DEFAULT 0 CHECK (tool_calls >= 0),
  input_tokens INTEGER NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
  output_tokens INTEGER NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
  CHECK ((ended_at IS NULL) = (outcome IS NULL)),
  CHECK (reason IS NULL OR ended_at IS NOT NULL)
);
CREATE INDEX ix_run_user ON agent_run(user_id, started_at);

-- The intents a run proposed, in order. RunRepository.forIntent reads by intent.
CREATE TABLE agent_run_intent (
  run_id TEXT NOT NULL REFERENCES agent_run(id),
  seq INTEGER NOT NULL CHECK (seq >= 0),
  intent_id TEXT NOT NULL,
  PRIMARY KEY (run_id, seq)
);
CREATE INDEX ix_run_intent ON agent_run_intent(intent_id);

-- Tool names and our own fixed codes only, never tool output (23.1).
CREATE TABLE agent_run_tool_event (
  run_id TEXT NOT NULL REFERENCES agent_run(id),
  seq INTEGER NOT NULL CHECK (seq >= 0),
  tool TEXT NOT NULL,
  summary TEXT NOT NULL,
  took_ms INTEGER NOT NULL CHECK (took_ms >= 0),
  PRIMARY KEY (run_id, seq)
);

-- A run's turns (23.1, m6-plan Decision 3): turn 0 is the start, each continuation adds one. P5 counts turns, so a
-- continuation counts as a run. user_id is copied from the run in the same INSERT that checks the owner, so the hourly
-- count reads one index. A turn holds a time only, never message text.
CREATE TABLE agent_run_turn (
  run_id TEXT NOT NULL REFERENCES agent_run(id),
  seq INTEGER NOT NULL CHECK (seq >= 0),
  user_id TEXT NOT NULL,
  started_at TEXT NOT NULL,
  PRIMARY KEY (run_id, seq)
);
CREATE INDEX ix_run_turn_user ON agent_run_turn(user_id, started_at);
