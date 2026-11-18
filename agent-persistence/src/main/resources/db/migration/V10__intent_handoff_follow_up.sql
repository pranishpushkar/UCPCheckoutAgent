-- The handoff follow-up (DESIGN 7.6, 10): after the move to HANDED_OFF the recovery worker reads the checkout until it
-- shows the store's order, ends, or the window closes. handoff_watch_until ends the window and handoff_next_read_at is the
-- next read; both are cleared when the follow-up ends. store_order_id and store_order_label are the order the store
-- reported (never the agent's order_id) and its confirmation code (VERIFY 11), returned only to the owner. Each belongs to
-- HANDED_OFF only, as handoff_url does (V9). Existing rows get none, so an intent handed off before V10 is not followed.
ALTER TABLE purchase_intent ADD COLUMN handoff_watch_until TEXT
  CHECK (handoff_watch_until IS NULL OR state = 'HANDED_OFF');
ALTER TABLE purchase_intent ADD COLUMN handoff_next_read_at TEXT
  CHECK (handoff_next_read_at IS NULL OR state = 'HANDED_OFF');
ALTER TABLE purchase_intent ADD COLUMN store_order_id TEXT
  CHECK (store_order_id IS NULL OR state = 'HANDED_OFF');
ALTER TABLE purchase_intent ADD COLUMN store_order_label TEXT
  CHECK (store_order_label IS NULL OR store_order_id IS NOT NULL);

-- The worker's claim (7.6, Appendix B claimRecoverable) finds the due follow-ups by their next read.
CREATE INDEX ix_intent_follow_up ON purchase_intent(handoff_next_read_at) WHERE handoff_watch_until IS NOT NULL;
