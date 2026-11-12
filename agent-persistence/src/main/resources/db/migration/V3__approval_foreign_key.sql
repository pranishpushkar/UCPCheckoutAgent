-- An approval belongs to an intent: add FOREIGN KEY (intent_id) REFERENCES purchase_intent(id).
-- SQLite cannot add a constraint to an existing table, so the table is rebuilt with standard statements (V2 stays as
-- applied). A row whose intent does not exist makes the copy fail, which is the point.

CREATE TABLE approval_new (
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
  UNIQUE (intent_id, seq),
  FOREIGN KEY (intent_id) REFERENCES purchase_intent(id)
);

INSERT INTO approval_new (id, intent_id, seq, user_id, snapshot_json, snapshot_hash, token_hash, expires_at, status,
                          decided_at, decided_by)
  SELECT id, intent_id, seq, user_id, snapshot_json, snapshot_hash, token_hash, expires_at, status, decided_at, decided_by
  FROM approval;

DROP TABLE approval;

ALTER TABLE approval_new RENAME TO approval;
