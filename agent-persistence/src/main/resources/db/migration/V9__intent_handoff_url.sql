-- The handoff path (DESIGN 7.1, 7.2, 10, H5): HANDED_OFF joins the intent states and the terminal states, and the intent
-- keeps the merchant's continue_url once it is handed off.
--
-- handoff_url: set once, in the same fenced write as the move to HANDED_OFF (7.2 hand_off); returned only to the owner
-- (23.2), never logged, never in an event. NULL in every other state.
--
-- SQLite cannot change a CHECK on an existing table, so, as V4 did, the four tables are rebuilt with standard
-- statements: the new tables are created and filled first, the old children and then the old parent are dropped, and
-- the new tables are renamed. Every row keeps every value it had; handoff_url starts NULL. The indexes and the V7
-- trigger are recreated, ux_intent_active_dedupe with HANDED_OFF among the terminal states (it must match
-- IntentState.isTerminal, tested). V1 to V8 stay as applied.

CREATE TABLE purchase_intent_new (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  merchant_id TEXT NOT NULL,
  dedupe_key TEXT NOT NULL,
  lines_json TEXT NOT NULL,
  buyer_email TEXT NOT NULL,
  state TEXT NOT NULL CHECK (state IN ('PROPOSED','QUOTING','AWAITING_APPROVAL','APPROVED','COMPLETING','RECONCILING',
                                       'COMPLETED','HANDED_OFF','REJECTED','FAILED_NOT_CHARGED','ESCALATED')),
  version INTEGER NOT NULL CHECK (version >= 0),
  session_id TEXT,
  approved_snapshot_hash TEXT,
  requote_count INTEGER NOT NULL DEFAULT 0 CHECK (requote_count >= 0),
  order_id TEXT,
  failure_reason TEXT,
  uncertain_since TEXT,
  cancel_pending INTEGER NOT NULL DEFAULT 0 CHECK (cancel_pending IN (0, 1)),
  lease_owner TEXT,
  lease_until TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  merchant_fingerprint TEXT NOT NULL DEFAULT '{"completion":"agent","owned":true,"transport":"rest"}',
  handoff_url TEXT,
  CHECK (state <> 'COMPLETED' OR order_id IS NOT NULL)
);

CREATE TABLE intent_event_new (
  intent_id TEXT NOT NULL REFERENCES purchase_intent_new(id),
  seq INTEGER NOT NULL,
  type TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  at TEXT NOT NULL,
  PRIMARY KEY (intent_id, seq)
);

CREATE TABLE merchant_attempt_new (
  id TEXT PRIMARY KEY,
  intent_id TEXT NOT NULL REFERENCES purchase_intent_new(id),
  seq INTEGER NOT NULL,
  op TEXT NOT NULL CHECK (op IN ('CREATE','UPDATE','COMPLETE','CANCEL','GET')),
  revision INTEGER NOT NULL CHECK (revision >= 0),
  idempotency_key TEXT,
  request_hash TEXT,
  request_body TEXT,
  status TEXT NOT NULL CHECK (status IN ('SENT','RESPONDED','UNCERTAIN')),
  http_status INTEGER,
  response_json TEXT,
  sent_at TEXT NOT NULL,
  responded_at TEXT,
  UNIQUE (intent_id, seq)
);

CREATE TABLE approval_new (
  id TEXT PRIMARY KEY,
  intent_id TEXT NOT NULL REFERENCES purchase_intent_new(id),
  seq INTEGER NOT NULL,
  user_id TEXT NOT NULL,
  snapshot_json TEXT NOT NULL,
  snapshot_hash TEXT NOT NULL,
  token_hash TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED','EXPIRED','CONSUMED')),
  decided_at TEXT,
  decided_by TEXT,
  UNIQUE (intent_id, seq)
);

INSERT INTO purchase_intent_new (id, user_id, merchant_id, dedupe_key, lines_json, buyer_email, state, version,
                                 session_id, approved_snapshot_hash, requote_count, order_id, failure_reason,
                                 uncertain_since, cancel_pending, lease_owner, lease_until, created_at, updated_at,
                                 merchant_fingerprint)
  SELECT id, user_id, merchant_id, dedupe_key, lines_json, buyer_email, state, version, session_id,
         approved_snapshot_hash, requote_count, order_id, failure_reason, uncertain_since, cancel_pending, lease_owner,
         lease_until, created_at, updated_at, merchant_fingerprint
  FROM purchase_intent;

INSERT INTO intent_event_new (intent_id, seq, type, payload_json, at)
  SELECT intent_id, seq, type, payload_json, at FROM intent_event;

INSERT INTO merchant_attempt_new (id, intent_id, seq, op, revision, idempotency_key, request_hash, request_body, status,
                                  http_status, response_json, sent_at, responded_at)
  SELECT id, intent_id, seq, op, revision, idempotency_key, request_hash, request_body, status, http_status,
         response_json, sent_at, responded_at
  FROM merchant_attempt;

INSERT INTO approval_new (id, intent_id, seq, user_id, snapshot_json, snapshot_hash, token_hash, expires_at, status,
                          decided_at, decided_by)
  SELECT id, intent_id, seq, user_id, snapshot_json, snapshot_hash, token_hash, expires_at, status, decided_at,
         decided_by
  FROM approval;

DROP TABLE approval;
DROP TABLE merchant_attempt;
DROP TABLE intent_event;
DROP TABLE purchase_intent;

ALTER TABLE purchase_intent_new RENAME TO purchase_intent;
ALTER TABLE intent_event_new RENAME TO intent_event;
ALTER TABLE merchant_attempt_new RENAME TO merchant_attempt;
ALTER TABLE approval_new RENAME TO approval;

-- Recreated: dropping a table drops its indexes and triggers. The terminal list must match IntentState.isTerminal.
CREATE UNIQUE INDEX ux_intent_active_dedupe ON purchase_intent(dedupe_key)
  WHERE state NOT IN ('COMPLETED','HANDED_OFF','REJECTED','FAILED_NOT_CHARGED','ESCALATED');
CREATE INDEX ix_intent_user ON purchase_intent(user_id, created_at);
CREATE UNIQUE INDEX ux_intent_order ON purchase_intent(merchant_id, order_id) WHERE order_id IS NOT NULL;
CREATE INDEX ix_intent_state_created ON purchase_intent(state, created_at, id);
CREATE INDEX ix_intent_cancel_pending ON purchase_intent(created_at, id) WHERE cancel_pending = 1;
CREATE INDEX ix_approval_pending_expiry ON approval(expires_at) WHERE status = 'PENDING';

-- I11 (V7): an intent belongs to exactly one merchant for its whole life.
CREATE TRIGGER purchase_intent_merchant_immutable
BEFORE UPDATE OF merchant_id ON purchase_intent
WHEN NEW.merchant_id IS NOT OLD.merchant_id
BEGIN
  SELECT RAISE(ABORT, 'I11: merchant_id never changes');
END;
