-- The user's budget (DESIGN 9.1 P9, 10, 23.1): an amount in minor units and a currency, from the user's own Budget field
-- on POST /api/runs, stored on the run and copied onto each intent the run proposes. Both columns or neither; at least one
-- minor unit; three upper-case letters. Existing rows get none, so nothing proposed before V11 has a budget.
ALTER TABLE agent_run ADD COLUMN budget_minor INTEGER
  CHECK (budget_minor IS NULL OR (budget_minor >= 1 AND budget_minor = CAST(budget_minor AS INTEGER)));
ALTER TABLE agent_run ADD COLUMN budget_currency TEXT
  CHECK ((budget_currency IS NULL) = (budget_minor IS NULL)
         AND (budget_currency IS NULL OR (length(budget_currency) = 3
              AND substr(budget_currency, 1, 1) BETWEEN 'A' AND 'Z'
              AND substr(budget_currency, 2, 1) BETWEEN 'A' AND 'Z'
              AND substr(budget_currency, 3, 1) BETWEEN 'A' AND 'Z')));

ALTER TABLE purchase_intent ADD COLUMN budget_minor INTEGER
  CHECK (budget_minor IS NULL OR (budget_minor >= 1 AND budget_minor = CAST(budget_minor AS INTEGER)));
ALTER TABLE purchase_intent ADD COLUMN budget_currency TEXT
  CHECK ((budget_currency IS NULL) = (budget_minor IS NULL)
         AND (budget_currency IS NULL OR (length(budget_currency) = 3
              AND substr(budget_currency, 1, 1) BETWEEN 'A' AND 'Z'
              AND substr(budget_currency, 2, 1) BETWEEN 'A' AND 'Z'
              AND substr(budget_currency, 3, 1) BETWEEN 'A' AND 'Z')));

-- P9, I3: an intent's budget is written once, at insert. The repositories never write it in an update; this trigger is
-- the backstop for any other writer, as V7's is for merchant_id.
CREATE TRIGGER purchase_intent_budget_immutable
BEFORE UPDATE OF budget_minor, budget_currency ON purchase_intent
WHEN NEW.budget_minor IS NOT OLD.budget_minor OR NEW.budget_currency IS NOT OLD.budget_currency
BEGIN
  SELECT RAISE(ABORT, 'P9: an intent''s budget never changes');
END;
