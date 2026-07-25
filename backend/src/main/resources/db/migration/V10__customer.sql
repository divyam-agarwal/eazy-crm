CREATE TABLE customer (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL,
    business_name    VARCHAR(255) NOT NULL,
    gstin            VARCHAR(15),
    state_code       VARCHAR(2) NOT NULL,
    billing_address  VARCHAR(512),
    shipping_address VARCHAR(512),
    credit_days      INTEGER NOT NULL DEFAULT 0,
    assigned_to      UUID,
    price_list_id    UUID,
    source           VARCHAR(16) NOT NULL,
    is_active        BOOLEAN NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ,
    version          BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_customer_tenant_gstin UNIQUE (tenant_id, gstin)
);
CREATE INDEX idx_customer_tenant ON customer (tenant_id, id);

ALTER TABLE customer ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON customer
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
