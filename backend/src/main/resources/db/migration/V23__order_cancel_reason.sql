-- Cancellation reason, mirroring enquiry.lost_reason. Nullable: null on every order
-- that was never cancelled. No status-column change is needed — status is already
-- VARCHAR(16) and the longest new enum name (DISPATCHED) is 10 characters.
ALTER TABLE sales_order ADD COLUMN cancel_reason VARCHAR(500);
