-- One quotation per enquiry per tenant (structural backstop for the entity terminal guard).
-- Postgres treats NULLs as distinct, so enquiry-less quotations (enquiry_id IS NULL) coexist freely.
ALTER TABLE quotation
    ADD CONSTRAINT uq_quotation_tenant_enquiry UNIQUE (tenant_id, enquiry_id);
