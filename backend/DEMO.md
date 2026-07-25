# EasyCRM — Tenant Isolation Demo (SYNTHETIC data)

All data below is synthetic. GSTINs, if shown, are checksum-valid but fabricated.

## Run
    cd backend
    docker compose up -d
    SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun

Two synthetic tenants are seeded: `alpha-traders` (state 27) and
`bravo-distributors` (state 29), each with a couple of demo records.

## Proof 1 — cross-tenant read returns 404 (not 403)
1. Mint an OWNER token for Alpha (via the login endpoint once P0-auth lands; for
   now, `JwtService.mint(alphaTenantId, someUser, "OWNER")`).
2. Find a Bravo record id (query as Bravo, or read the seeder logs).
3. Request Bravo's record while holding Alpha's token:

       curl -i -H "Authorization: Bearer <ALPHA_TOKEN>" \
            localhost:8080/api/v1/demo-records/<BRAVO_RECORD_ID>

   → **HTTP 404** — Alpha cannot even confirm the record exists.

## Proof 2 — raw SQL with no tenant context returns zero rows
Connect as the NON-OWNER app role and query without setting the tenant GUC:

    psql "postgresql://easycrm_app:easycrm_app@localhost:5432/easycrm" \
      -c "SELECT count(*) FROM demo_record;"
    -- count = 0  (Row-Level Security blocks the non-owner role)

Now set the tenant and see only that tenant's rows appear:

    psql "postgresql://easycrm_app:easycrm_app@localhost:5432/easycrm" -c "
      BEGIN;
      SELECT set_config('app.current_tenant', '<ALPHA_TENANT_ID>', true);
      SELECT count(*) FROM demo_record;
      COMMIT;"
    -- count = 2  (only Alpha's rows)

Proof 2 is the important half: it shows the DATABASE enforces isolation,
independent of any application code.

## The four layers being demonstrated
1. **JWT resolution** — tenant comes only from the signed token (Proof 1).
2. **Hibernate `@TenantId`** — every ORM query auto-filtered by tenant.
3. **PostgreSQL RLS** — the database itself refuses foreign rows (Proof 2).
4. **ArchUnit** — the build fails if any `@Entity` forgets tenant scoping
   (`./gradlew test --tests "*TenantScopingArchTest"`).
