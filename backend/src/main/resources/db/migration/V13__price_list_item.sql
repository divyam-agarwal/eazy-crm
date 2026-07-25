CREATE TABLE price_list_item (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    price_list_id UUID NOT NULL,
    product_id    UUID NOT NULL,
    override_rate NUMERIC(18,2),
    discount_pct  NUMERIC(18,4),
    created_at    TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ,
    version       BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_pli_tenant_list_product UNIQUE (tenant_id, price_list_id, product_id),
    CONSTRAINT ck_pli_rate_xor CHECK (num_nonnulls(override_rate, discount_pct) = 1)
);
CREATE INDEX idx_pli_tenant ON price_list_item (tenant_id, id);
CREATE INDEX idx_pli_list ON price_list_item (tenant_id, price_list_id);

ALTER TABLE price_list_item ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON price_list_item
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
