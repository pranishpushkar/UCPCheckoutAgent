-- Recovery indexes and the schema's own guards for at-most-one-order (m3-plan section 5, design 7.6 and 10).
--
-- SQLite cannot add a CHECK to an existing table, so the four tables are rebuilt with standard statements: the new
-- tables are created and filled first, the old children and then the old parent are dropped, and the new tables are
-- renamed. Renaming the new parent makes the new children's foreign keys follow it. Existing rows are kept; a row that
-- breaks a new check makes the copy fail, which is the point. V1 to V3 stay as applied.
--
-- Guards added: state, op and status lists; COMPLETED needs an order_id; one order id per merchant; cancel_pending is
-- 0 or 1; version and counters are not negative. Deliberately absent: UNIQUE (intent_id) WHERE status = 'SENT', which
-- could strand an intent after a crash (m3-plan section 5).

CREATE TABLE purchase_intent_new (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  merchant_id TEXT NOT NULL,
  dedupe_key TEXT NOT NULL,
  lines_json TEXT NOT NULL,
  buyer_email TEXT NOT NULL,
  state TEXT NOT NULL CHECK (state IN ('PROPOSED','QUOTING','AWAITING_APPROVAL','APPROVED','COMPLETING','RECONCILING',
                                       'COMPLETED','REJECTED','FAILED_NOT_CHARGED','ESCALATED')),
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
                                 uncertain_since, cancel_pending, lease_owner, lease_until, created_at, updated_at)
  SELECT id, user_id, merchant_id, dedupe_key, lines_json, buyer_email, state, version, session_id,
         approved_snapshot_hash, requote_count, order_id, failure_reason, uncertain_since, cancel_pending, lease_owner,
         lease_until, created_at, updated_at
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

-- Recreated: dropping a table drops its indexes. The terminal list must match IntentState.isTerminal (tested).
CREATE UNIQUE INDEX ux_intent_active_dedupe ON purchase_intent(dedupe_key)
  WHERE state NOT IN ('COMPLETED','REJECTED','FAILED_NOT_CHARGED','ESCALATED');
CREATE INDEX ix_intent_user ON purchase_intent(user_id, created_at);

-- At most one intent per merchant order.
CREATE UNIQUE INDEX ux_intent_order ON purchase_intent(merchant_id, order_id) WHERE order_id IS NOT NULL;

-- claimRecoverable (7.6): states a worker drives, oldest first, and the owed cancels.
CREATE INDEX ix_intent_state_created ON purchase_intent(state, created_at, id);
CREATE INDEX ix_intent_cancel_pending ON purchase_intent(created_at, id) WHERE cancel_pending = 1;

-- RecoveryScan.approvalsDue (7.6): pending approvals by expiry.
CREATE INDEX ix_approval_pending_expiry ON approval(expires_at) WHERE status = 'PENDING';
