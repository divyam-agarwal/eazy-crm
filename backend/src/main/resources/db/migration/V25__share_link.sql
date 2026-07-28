-- GLOBAL table: deliberately NO row-level security and NO @TenantId.
-- The public share endpoint has no JWT, so it has no tenant; this row is what
-- resolves one. Everything it points at is then read through @TenantId + RLS as
-- normal. It holds no document data — only an opaque token, a tenant and a version.
CREATE TABLE share_link (
    id                   UUID PRIMARY KEY,
    token                VARCHAR(64) NOT NULL UNIQUE,
    tenant_id            UUID NOT NULL,
    quotation_version_id UUID NOT NULL,
    created_at           TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ,
    version              BIGINT NOT NULL DEFAULT 0
);

-- One link per version: sharing the same version twice returns the same URL, so a
-- link already sent to a customer keeps working.
CREATE UNIQUE INDEX uq_share_link_version ON share_link (quotation_version_id);
