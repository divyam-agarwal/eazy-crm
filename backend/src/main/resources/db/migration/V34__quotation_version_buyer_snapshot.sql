-- F11: QuotationVersion froze the items, the totals, the terms and the place of supply,
-- but not the buyer. QuotationPdfService read businessName, gstin and billingAddress live
-- from `customer` at render time, so editing a customer silently changed a document that
-- had already been sent — including through a public share link the buyer already held.
--
-- Nullable on purpose: a DRAFT has no buyer frozen yet. The invariant is not-null when
-- SENT, and QuotationService.send() is what establishes it. A NOT NULL column with a
-- placeholder default would make "never frozen" indistinguishable from a real value.
--
-- Widths mirror `customer` exactly. A snapshot narrower than its source truncates on freeze.
ALTER TABLE quotation_version
  ADD COLUMN buyer_business_name   VARCHAR(255),
  ADD COLUMN buyer_gstin           VARCHAR(15),
  ADD COLUMN buyer_billing_address VARCHAR(512);

-- The backfill has to cross tenants, and it CANNOT do so by ignoring RLS.
--
-- Flyway connects as easycrm_owner (spring.flyway.user), which owns these tables, and
-- V26 FORCEd row-level security precisely so the owner is bound by it too. Every policy
-- in this schema reads:
--
--     USING (tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid)
--
-- The `true` is missing_ok. In a Flyway session nothing sets that GUC, so current_setting
-- returns NULL, the comparison is NULL, and it is never true. A plain cross-tenant UPDATE
-- here would match ZERO ROWS, commit, and report success — no error, no warning, no log
-- line. V26's own header predicted this by name: "a migration tool reused for a backfill".
-- This is the repo's first DML migration and so the first to meet it.
--
-- Rejected: NO FORCE around the backfill (a mid-migration failure leaves the table
-- unforced — a silent isolation hole introduced by the fix for a silent bug), and
-- BYPASSRLS on easycrm_owner (a permanent role attribute weakening layer 3 forever to
-- serve one migration). Instead: drive it per tenant from INSIDE the policy. `tenant` has
-- no tenant_id column and no RLS, so the registry itself is readable here.
DO $$
DECLARE
  t     uuid;
  stale integer;
BEGIN
  FOR t IN SELECT id FROM tenant LOOP
    PERFORM set_config('app.current_tenant', t::text, true);

    UPDATE quotation_version v
       SET buyer_business_name   = c.business_name,
           buyer_gstin           = c.gstin,
           buyer_billing_address = c.billing_address
      FROM quotation q
      JOIN customer c ON c.id = q.customer_id
     WHERE q.id = v.quotation_id
       AND v.status = 'SENT';

    -- This check MUST live inside the loop. Written after it, with the GUC reset, it
    -- would see zero rows through RLS and pass vacuously — as silently broken as the bug
    -- it exists to catch, and in exactly the same way.
    SELECT count(*) INTO stale
      FROM quotation_version
     WHERE status = 'SENT'
       AND buyer_business_name IS NULL;

    IF stale > 0 THEN
      RAISE EXCEPTION 'tenant %: % SENT quotation versions left unfrozen', t, stale;
    END IF;
  END LOOP;

  -- set_config(..., true) is transaction-local and Flyway wraps each migration in one,
  -- so this cannot leak; reset anyway, so nothing later in this file inherits a tenant.
  PERFORM set_config('app.current_tenant', '', true);
END $$;
