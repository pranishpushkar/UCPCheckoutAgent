-- I11 (DESIGN 4.3, 19): an intent belongs to exactly one merchant for its whole life. The repositories refuse an update
-- that names another merchant; this trigger is the backstop for any other writer. It adds an object and rewrites no row.
CREATE TRIGGER purchase_intent_merchant_immutable
BEFORE UPDATE OF merchant_id ON purchase_intent
WHEN NEW.merchant_id IS NOT OLD.merchant_id
BEGIN
  SELECT RAISE(ABORT, 'I11: merchant_id never changes');
END;
