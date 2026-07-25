CREATE TABLE refresh_token (
    id             UUID PRIMARY KEY,
    token_hash     VARCHAR(64) NOT NULL UNIQUE,
    user_id        UUID NOT NULL,
    tenant_id      UUID NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    revoked_at     TIMESTAMPTZ,
    replaced_by_id UUID,
    created_at     TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ,
    version        BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_refresh_token_hash ON refresh_token (token_hash);
-- GLOBAL table: NO row-level security. Looked up by unguessable hash; hashed at rest.
