CREATE TABLE demo_record (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    label      VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    version    BIGINT NOT NULL DEFAULT 0
);
-- tenant_id is the leading column of this index: the RLS predicate (added in V4)
-- is a plain indexed equality filter, so the planner treats it as a normal filter.
CREATE INDEX idx_demo_record_tenant ON demo_record (tenant_id, id);
