-- Section 10 app_user and user_session (M6, for the local sign-in of 18.8; m6-plan section 4). Portable SQL, see V1.
-- login_state arrives with OIDC in M6b. A session is stored by the SHA-256 hash of its cookie value, never the value
-- (18.5).

CREATE TABLE app_user (
  id TEXT PRIMARY KEY,
  email TEXT NOT NULL,
  created_at TEXT NOT NULL,
  last_login_at TEXT NOT NULL
);

CREATE TABLE user_session (
  id_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  csrf_token TEXT NOT NULL,
  auth_time TEXT NOT NULL,
  created_at TEXT NOT NULL,
  last_seen_at TEXT NOT NULL,
  expires_at TEXT NOT NULL
);
CREATE INDEX ix_session_user ON user_session(user_id);
