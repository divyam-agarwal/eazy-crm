CREATE TABLE audit_log (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    actor_user_id UUID,
    action        VARCHAR(64) NOT NULL,
    detail        JSONB,
    created_at    TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ,
    version       BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_audit_tenant ON audit_log (tenant_id, created_at);

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_log
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
