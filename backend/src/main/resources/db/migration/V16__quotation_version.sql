CREATE TABLE quotation_version (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL,
    quotation_id    UUID NOT NULL,
    version_no      INT NOT NULL,
    status          VARCHAR(16) NOT NULL,
    valid_until     DATE,
    payment_terms   TEXT,
    delivery_terms  TEXT,
    notes           TEXT,
    place_of_supply VARCHAR(2) NOT NULL,
    sub_total       NUMERIC(18,2) NOT NULL,
    total_tax       NUMERIC(18,2) NOT NULL,
    grand_total     NUMERIC(18,2) NOT NULL,
    sent_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ,
    version         BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_qv_tenant_quotation_no UNIQUE (tenant_id, quotation_id, version_no)
);
CREATE INDEX idx_qv_tenant ON quotation_version (tenant_id, id);
CREATE INDEX idx_qv_quotation ON quotation_version (tenant_id, quotation_id);

ALTER TABLE quotation_version ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON quotation_version
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
