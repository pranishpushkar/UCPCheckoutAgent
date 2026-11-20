-- The buyer country (DESIGN 10, 23.1, 26.7): an ISO 3166-1 alpha-2 code from the user's Deliver to choice on
-- POST /api/runs, or the operator's BUYER_COUNTRY, stored on the run and copied onto each intent the run proposes. Two
-- upper-case letters; never null. Existing rows get US, which is what they were searched for. That the code names a
-- country with one currency is BuyerCountry's rule, checked before any write.
ALTER TABLE agent_run ADD COLUMN ship_to_country TEXT NOT NULL DEFAULT 'US'
  CHECK (length(ship_to_country) = 2
         AND substr(ship_to_country, 1, 1) BETWEEN 'A' AND 'Z'
         AND substr(ship_to_country, 2, 1) BETWEEN 'A' AND 'Z');

ALTER TABLE purchase_intent ADD COLUMN ship_to_country TEXT NOT NULL DEFAULT 'US'
  CHECK (length(ship_to_country) = 2
         AND substr(ship_to_country, 1, 1) BETWEEN 'A' AND 'Z'
         AND substr(ship_to_country, 2, 1) BETWEEN 'A' AND 'Z');

-- 23.1: an intent's buyer country is written once, at insert. The repositories never write it in an update; this
-- trigger is the backstop for any other writer, as V11's is for the budget.
CREATE TRIGGER purchase_intent_ship_to_immutable
BEFORE UPDATE OF ship_to_country ON purchase_intent
WHEN NEW.ship_to_country IS NOT OLD.ship_to_country
BEGIN
  SELECT RAISE(ABORT, '23.1: an intent''s buyer country never changes');
END;
