CREATE TABLE sales_order (
    id                   UUID PRIMARY KEY,
    tenant_id            UUID NOT NULL,
    order_no             VARCHAR(32) NOT NULL,
    quotation_id         UUID NOT NULL,
    quotation_version_id UUID NOT NULL,
    customer_id          UUID NOT NULL,
    po_reference         VARCHAR(255),
    po_date              DATE,
    sub_total            NUMERIC(18,2) NOT NULL,
    total_tax            NUMERIC(18,2) NOT NULL,
    grand_total          NUMERIC(18,2) NOT NULL,
    status               VARCHAR(16) NOT NULL,
    created_at           TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ,
    version              BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_order_tenant_no UNIQUE (tenant_id, order_no),
    CONSTRAINT uq_order_tenant_quotation UNIQUE (tenant_id, quotation_id)
);
CREATE INDEX idx_order_tenant ON sales_order (tenant_id, id);
CREATE INDEX idx_order_customer ON sales_order (tenant_id, customer_id);
