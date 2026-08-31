CREATE TABLE follow_up (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL,
    subject_type     VARCHAR(16) NOT NULL,
    subject_id       UUID NOT NULL,
    due_at           TIMESTAMPTZ NOT NULL,
    assigned_to      UUID NOT NULL,
    status           VARCHAR(16) NOT NULL,
    note             VARCHAR(500),
    completed_at     TIMESTAMPTZ,
    completion_note  VARCHAR(500),
    created_by       UUID,
    created_at       TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ,
    version          BIGINT NOT NULL DEFAULT 0
);

-- The dashboard's hottest query, run on every login: my pending follow-ups ordered by
-- due date. The record-visibility slice shipped its assigned_to predicate on customer and
-- enquiry with no index behind it (HANDOFF §8, "Before the first large tenant"); getting
-- it right at creation costs one line, retrofitting costs a migration on a live table.
CREATE INDEX idx_follow_up_owner_due
    ON follow_up (tenant_id, assigned_to, status, due_at);

CREATE INDEX idx_follow_up_subject
    ON follow_up (tenant_id, subject_type, subject_id);
