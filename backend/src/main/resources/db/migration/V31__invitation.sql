-- GLOBAL table: deliberately NO row-level security and NO @TenantId, for the same reason
-- as refresh_token and share_link. Accepting an invitation happens with no JWT, so there
-- is no tenant to filter by — this row is what resolves one. The user it creates is then
-- written through @TenantId + RLS as normal.
--
-- Registered in BOTH TenantScopingArchTest.GLOBAL_TABLES (layer 2) and
-- RlsCoverageIntegrationTest.GLOBAL_TABLES (layer 3). Omitting either fails the build.
CREATE TABLE invitation (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL,
    email            VARCHAR(255) NOT NULL,
    role             VARCHAR(16) NOT NULL,
    -- VARCHAR, not CHAR: Hibernate maps String to varchar and ddl-auto: validate would
    -- reject a bpchar column. refresh_token.token_hash is VARCHAR(64) for the same reason.
    token_hash       VARCHAR(64) NOT NULL,
    status           VARCHAR(16) NOT NULL,
    expires_at       TIMESTAMPTZ NOT NULL,
    invited_by       UUID NOT NULL,
    accepted_at      TIMESTAMPTZ,
    accepted_user_id UUID,
    created_at       TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ,
    version          BIGINT NOT NULL DEFAULT 0
);

-- The accept lookup, and the uniqueness the token's security relies on.
CREATE UNIQUE INDEX uq_invitation_token_hash ON invitation (token_hash);

-- At most one LIVE invitation per address per tenant. PARTIAL, so accepted and revoked
-- rows accumulate freely as history. lower(email) so a second invite to a case variant
-- ("Ravi@shop.in" vs "ravi@shop.in") collides too. This makes a double-invite a
-- database-level conflict rather than a check-then-act race in the service.
CREATE UNIQUE INDEX uq_invitation_pending_email
    ON invitation (tenant_id, lower(email))
    WHERE status = 'PENDING';

-- The owner's pending list — the only list query this slice adds. Shipped in the creating
-- migration per the standing agreement (HANDOFF §8): one line now, a migration on a live
-- table later.
CREATE INDEX idx_invitation_tenant_status ON invitation (tenant_id, status, expires_at);
