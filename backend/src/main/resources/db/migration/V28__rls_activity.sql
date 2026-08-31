ALTER TABLE activity ENABLE ROW LEVEL SECURITY;
ALTER TABLE activity FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON activity
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
