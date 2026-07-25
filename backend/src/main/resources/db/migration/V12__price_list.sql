CREATE TABLE price_list (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    name       VARCHAR(255) NOT NULL,
    is_active  BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    version    BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_price_list_tenant_name UNIQUE (tenant_id, name)
);
CREATE INDEX idx_price_list_tenant ON price_list (tenant_id, id);

ALTER TABLE price_list ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON price_list
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
