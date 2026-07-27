CREATE TABLE enquiry (
    id                UUID PRIMARY KEY,
    tenant_id         UUID NOT NULL,
    customer_id       UUID,
    contact_name      VARCHAR(200) NOT NULL,
    contact_phone     VARCHAR(20) NOT NULL,
    normalized_phone  VARCHAR(10) NOT NULL,
    contact_email     VARCHAR(254),
    source            VARCHAR(16) NOT NULL,
    requirement_text  VARCHAR(2000),
    assigned_to       UUID,
    stage             VARCHAR(16) NOT NULL,
    expected_value    NUMERIC(18,2),
    lost_reason       VARCHAR(500),
    created_at        TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ,
    version           BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_enquiry_tenant ON enquiry (tenant_id, id);

-- One active (non-terminal) enquiry per phone per tenant. Terminal enquiries
-- (CONVERTED/LOST) drop out of the predicate, freeing the phone for a fresh lead.
CREATE UNIQUE INDEX uq_enquiry_tenant_active_phone
    ON enquiry (tenant_id, normalized_phone)
    WHERE stage NOT IN ('CONVERTED', 'LOST');
