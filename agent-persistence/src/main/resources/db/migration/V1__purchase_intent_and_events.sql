-- Section 10. Portable SQL: no AUTOINCREMENT, no SQLite-only syntax (checked by PortableSqlLintTest).
-- Times are fixed-width UTC text (SqlTimes), so text order is time order.

CREATE TABLE purchase_intent (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  merchant_id TEXT NOT NULL,
  dedupe_key TEXT NOT NULL,
  lines_json TEXT NOT NULL,
  buyer_email TEXT NOT NULL,
  state TEXT NOT NULL,
  version INTEGER NOT NULL,
  session_id TEXT,
  approved_snapshot_hash TEXT,
  requote_count INTEGER NOT NULL DEFAULT 0,
  order_id TEXT,
  failure_reason TEXT,
  uncertain_since TEXT,
  cancel_pending INTEGER NOT NULL DEFAULT 0,
  lease_owner TEXT,
  lease_until TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

-- 7.7: one non-terminal intent per dedupe key. The terminal list must match IntentState.isTerminal (tested).
CREATE UNIQUE INDEX ux_intent_active_dedupe ON purchase_intent(dedupe_key)
  WHERE state NOT IN ('COMPLETED','REJECTED','FAILED_NOT_CHARGED','ESCALATED');

CREATE INDEX ix_intent_user ON purchase_intent(user_id, created_at);

CREATE TABLE intent_event (
  intent_id TEXT NOT NULL REFERENCES purchase_intent(id),
  seq INTEGER NOT NULL,
  type TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  at TEXT NOT NULL,
  PRIMARY KEY (intent_id, seq)
);
