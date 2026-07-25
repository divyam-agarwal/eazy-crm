CREATE TABLE contact (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL,
    customer_id     UUID NOT NULL,
    name            VARCHAR(255) NOT NULL,
    phone           VARCHAR(20),
    whatsapp_number VARCHAR(20),
    email           VARCHAR(255),
    designation     VARCHAR(128),
    is_primary      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ,
    version         BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_contact_tenant ON contact (tenant_id, id);
CREATE INDEX idx_contact_customer ON contact (tenant_id, customer_id);

ALTER TABLE contact ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON contact
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
