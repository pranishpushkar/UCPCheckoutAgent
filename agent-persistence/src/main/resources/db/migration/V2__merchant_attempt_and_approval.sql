-- Section 10. Portable SQL, see V1.

CREATE TABLE merchant_attempt (
  id TEXT PRIMARY KEY,
  intent_id TEXT NOT NULL REFERENCES purchase_intent(id),
  seq INTEGER NOT NULL,
  op TEXT NOT NULL,
  revision INTEGER NOT NULL,
  idempotency_key TEXT,
  request_hash TEXT,
  request_body TEXT,
  status TEXT NOT NULL,
  http_status INTEGER,
  response_json TEXT,
  sent_at TEXT NOT NULL,
  responded_at TEXT,
  UNIQUE (intent_id, seq)
);

-- No foreign key to purchase_intent: the repository contract inserts approvals for intents it never stored, and
-- I10 (approval owner = intent owner) is checked by the service, not by the schema.
CREATE TABLE approval (
  id TEXT PRIMARY KEY,
  intent_id TEXT NOT NULL,
  seq INTEGER NOT NULL,
  user_id TEXT NOT NULL,
  snapshot_json TEXT NOT NULL,
  snapshot_hash TEXT NOT NULL,
  token_hash TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  status TEXT NOT NULL,
  decided_at TEXT,
  decided_by TEXT,
  UNIQUE (intent_id, seq)
);
