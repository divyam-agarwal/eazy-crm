CREATE TABLE quotation_item (
    id             UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL,
    version_id     UUID NOT NULL,
    product_id     UUID,
    name_snapshot  VARCHAR(255) NOT NULL,
    hsn_snapshot   VARCHAR(8),
    uom_snapshot   VARCHAR(16) NOT NULL,
    qty            NUMERIC(18,3) NOT NULL,
    rate           NUMERIC(18,2) NOT NULL,
    discount_pct   NUMERIC(18,4),
    gst_rate       NUMERIC(18,4) NOT NULL,
    taxable_value  NUMERIC(18,2) NOT NULL,
    cgst           NUMERIC(18,2) NOT NULL,
    sgst           NUMERIC(18,2) NOT NULL,
    igst           NUMERIC(18,2) NOT NULL,
    line_total     NUMERIC(18,2) NOT NULL,
    created_at     TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ,
    version        BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_qi_tenant ON quotation_item (tenant_id, id);
CREATE INDEX idx_qi_version ON quotation_item (tenant_id, version_id);

ALTER TABLE quotation_item ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON quotation_item
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
