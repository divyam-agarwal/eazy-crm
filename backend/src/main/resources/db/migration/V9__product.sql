CREATE TABLE product (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    sku        VARCHAR(64) NOT NULL,
    name       VARCHAR(255) NOT NULL,
    hsn_code   VARCHAR(8),
    uom        VARCHAR(16) NOT NULL,
    gst_rate   NUMERIC(18,4) NOT NULL,
    base_rate  NUMERIC(18,2) NOT NULL,
    is_active  BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    version    BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_product_tenant_sku UNIQUE (tenant_id, sku)
);
CREATE INDEX idx_product_tenant ON product (tenant_id, id);

ALTER TABLE product ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON product
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
