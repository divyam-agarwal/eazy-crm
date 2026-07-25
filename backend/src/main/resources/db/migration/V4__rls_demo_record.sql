ALTER TABLE demo_record ENABLE ROW LEVEL SECURITY;

-- The app connects as the non-owner role easycrm_app, so this policy is enforced.
-- A custom GUC that has been referenced resets to '' (empty string), not NULL, after a
-- transaction-local set_config. ''::uuid throws, so wrap in NULLIF: '' -> NULL ->
-- tenant_id = NULL -> no rows. When a tenant IS set, it is a valid uuid and matches.
CREATE POLICY tenant_isolation ON demo_record
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
