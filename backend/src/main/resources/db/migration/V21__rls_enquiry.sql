ALTER TABLE enquiry ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON enquiry
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
