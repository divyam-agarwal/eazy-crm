CREATE TABLE activity (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    subject_type  VARCHAR(16) NOT NULL,
    subject_id    UUID NOT NULL,
    type          VARCHAR(16) NOT NULL,
    body          VARCHAR(2000),
    outcome       VARCHAR(200),
    occurred_at   TIMESTAMPTZ NOT NULL,
    logged_by     UUID,
    source        VARCHAR(8) NOT NULL,
    created_at    TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ,
    version       BIGINT NOT NULL DEFAULT 0
);

-- The timeline query: every activity read is scoped to one subject and ordered by
-- occurred_at DESC. Fully covered by this index.
CREATE INDEX idx_activity_subject
    ON activity (tenant_id, subject_type, subject_id, occurred_at DESC);
