-- A model_error's cause (DESIGN 6.5, 10, 23.1): what the run's last model call failed with, from 6.5's closed
-- vocabulary (http_429, finish_SAFETY, timeout, ...), so the console and the owner see it after a restart too. Only with
-- reason model_error, and 1 to 64 characters. Existing runs get none.
-- The [A-Za-z0-9_] rule is RunRecord's, checked before any write, and SqlRunRepository reads any other stored text as
-- unknown: a portable CHECK for it nests too deep for an SQLite with a fixed parser stack (section 10).
ALTER TABLE agent_run ADD COLUMN cause TEXT
  CHECK (cause IS NULL OR (reason = 'model_error' AND length(cause) BETWEEN 1 AND 64));
