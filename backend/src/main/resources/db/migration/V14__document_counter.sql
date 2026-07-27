CREATE TABLE document_counter (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    doc_type   VARCHAR(16) NOT NULL,
    fy         VARCHAR(7) NOT NULL,
    next_val   BIGINT NOT NULL,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    version    BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_doc_counter_tenant_type_fy UNIQUE (tenant_id, doc_type, fy)
);
CREATE INDEX idx_doc_counter_tenant ON document_counter (tenant_id, id);

ALTER TABLE document_counter ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON document_counter
    USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid);
