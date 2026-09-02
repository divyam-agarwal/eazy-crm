-- The record-visibility slice shipped the predicate
--     assigned_to = :me OR assigned_to IS NULL
-- on customer and enquiry with no index behind it, and members management now adds three
-- more reads keyed on assigned_to (the reassign-first gate). follow_up already ships its
-- equivalent, idx_follow_up_owner_due, from its own creating migration.
--
-- The house pattern is that the slice adding the query adds the index: one line here,
-- versus a migration against a live table later.
CREATE INDEX idx_customer_assigned ON customer (tenant_id, assigned_to);
CREATE INDEX idx_enquiry_assigned  ON enquiry  (tenant_id, assigned_to);
