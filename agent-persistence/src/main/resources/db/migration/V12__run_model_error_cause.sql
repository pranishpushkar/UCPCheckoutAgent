-- A model_error's cause (DESIGN 6.5, 10, 23.1): what the run's last model call failed with, from 6.5's closed
-- vocabulary (http_429, finish_SAFETY, timeout, ...), so the console and the owner see it after a restart too. Only with
-- reason model_error; 1 to 64 of [A-Za-z0-9_], so no message or text can be stored as one. Existing runs get none.
-- The character rule in portable SQL (ADR 0002, no GLOB or regex): with every allowed character removed from the
-- lower-cased cause, nothing is left. RunRecord checks the same before any write.
ALTER TABLE agent_run ADD COLUMN cause TEXT
  CHECK (cause IS NULL OR (reason = 'model_error' AND length(cause) BETWEEN 1 AND 64
         AND replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(lower(cause), 'a', ''), 'b', ''), 'c', ''), 'd', ''), 'e', ''), 'f', ''), 'g', ''), 'h', ''), 'i', ''), 'j', ''), 'k', ''), 'l', ''), 'm', ''), 'n', ''), 'o', ''), 'p', ''), 'q', ''), 'r', ''), 's', ''), 't', ''), 'u', ''), 'v', ''), 'w', ''), 'x', ''), 'y', ''), 'z', ''), '0', ''), '1', ''), '2', ''), '3', ''), '4', ''), '5', ''), '6', ''), '7', ''), '8', ''), '9', ''), '_', '') = ''));
