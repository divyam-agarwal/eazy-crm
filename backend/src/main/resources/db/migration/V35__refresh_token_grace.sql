-- Lost-response grace for refresh rotation (spec 2026-09-14-f0 §3.3).
-- When a rotation commits but its response never reaches the browser, the browser presents the
-- just-revoked token again. grace_used_at records that the single permitted re-presentation has
-- happened. refresh_token is a GLOBAL, RLS-exempt table (V7), so no per-tenant DML concern applies.
ALTER TABLE refresh_token ADD COLUMN grace_used_at TIMESTAMPTZ;
