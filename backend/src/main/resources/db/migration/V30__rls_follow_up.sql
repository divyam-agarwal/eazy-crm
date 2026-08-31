ALTER TABLE follow_up ENABLE ROW LEVEL SECURITY;
ALTER TABLE follow_up FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON follow_up
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
