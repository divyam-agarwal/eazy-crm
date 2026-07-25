CREATE TABLE tenant (
    id            UUID PRIMARY KEY,
    slug          VARCHAR(64) NOT NULL UNIQUE,
    business_name VARCHAR(255) NOT NULL,
    state_code    VARCHAR(2) NOT NULL,
    created_at    TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ,
    version       BIGINT NOT NULL DEFAULT 0
);
-- tenant is a GLOBAL table: no tenant_id, no RLS. It is the tenant registry itself.
