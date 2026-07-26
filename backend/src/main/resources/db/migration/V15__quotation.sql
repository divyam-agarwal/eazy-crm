CREATE TABLE quotation (
    id                 UUID PRIMARY KEY,
    tenant_id          UUID NOT NULL,
    quote_no           VARCHAR(32),
    customer_id        UUID NOT NULL,
    enquiry_id         UUID,
    current_version_id UUID,
    status             VARCHAR(16) NOT NULL,
    created_at         TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ,
    version            BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_quotation_tenant_no UNIQUE (tenant_id, quote_no)
);
CREATE INDEX idx_quotation_tenant ON quotation (tenant_id, id);
CREATE INDEX idx_quotation_customer ON quotation (tenant_id, customer_id);

ALTER TABLE quotation ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON quotation
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
