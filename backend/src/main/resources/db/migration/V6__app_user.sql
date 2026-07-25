CREATE TABLE app_user (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    email         VARCHAR(255) NOT NULL,
    phone         VARCHAR(20),
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(16) NOT NULL,
    status        VARCHAR(16) NOT NULL,
    created_at    TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ,
    version       BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_user_tenant_email UNIQUE (tenant_id, email)
);
CREATE INDEX idx_user_tenant ON app_user (tenant_id, id);

ALTER TABLE app_user ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app_user
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
