ALTER TABLE sales_order ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sales_order
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
