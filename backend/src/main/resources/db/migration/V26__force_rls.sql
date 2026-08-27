-- Layer 3 of the four-layer tenant isolation was only half-installed.
--
-- ENABLE ROW LEVEL SECURITY does not bind a table's OWNER: PostgreSQL exempts the
-- owner from its own policies unless the table is additionally FORCEd. Every table
-- below was ENABLEd and none was FORCEd, so isolation rested entirely on the
-- application happening to connect as the non-owner easycrm_app role. That holds for
-- one deployment connecting with one set of credentials. It stops holding the moment
-- any process connects as the owner — a migration tool reused for a backfill, a
-- second service issued the wrong role, an ops console — and it fails SILENTLY:
-- no error, no log line, no failing test, just cross-tenant rows.
--
-- FORCE closes that. It is inert for the app role (already bound) and for Flyway
-- (DDL only; RLS governs DML). Anything that legitimately needs to cross tenants
-- must now do so deliberately, with BYPASSRLS, rather than by accident of role.
--
-- RlsCoverageIntegrationTest keeps this list complete: any future table with a
-- tenant_id column that is not enabled, forced and policied fails the build by name.
ALTER TABLE app_user          FORCE ROW LEVEL SECURITY;
ALTER TABLE audit_log         FORCE ROW LEVEL SECURITY;
ALTER TABLE contact           FORCE ROW LEVEL SECURITY;
ALTER TABLE customer          FORCE ROW LEVEL SECURITY;
ALTER TABLE demo_record       FORCE ROW LEVEL SECURITY;
ALTER TABLE document_counter  FORCE ROW LEVEL SECURITY;
ALTER TABLE enquiry           FORCE ROW LEVEL SECURITY;
ALTER TABLE price_list        FORCE ROW LEVEL SECURITY;
ALTER TABLE price_list_item   FORCE ROW LEVEL SECURITY;
ALTER TABLE product           FORCE ROW LEVEL SECURITY;
ALTER TABLE quotation         FORCE ROW LEVEL SECURITY;
ALTER TABLE quotation_item    FORCE ROW LEVEL SECURITY;
ALTER TABLE quotation_version FORCE ROW LEVEL SECURITY;
ALTER TABLE sales_order       FORCE ROW LEVEL SECURITY;

-- Deliberately NOT forced, because deliberately not tenant-scoped:
--   tenant         — the tenant registry itself; has no tenant_id column.
--   refresh_token  — pre-auth session table, looked up by hash before a tenant exists.
--   share_link     — pre-auth share table; it is what RESOLVES the tenant for the
--                    public quotation link. Forcing it would break the only
--                    unauthenticated route in the app.
-- These three are exempted by name in RlsCoverageIntegrationTest.GLOBAL_TABLES and
-- TenantScopingArchTest.GLOBAL_TABLES; extend both together, and only with review.
