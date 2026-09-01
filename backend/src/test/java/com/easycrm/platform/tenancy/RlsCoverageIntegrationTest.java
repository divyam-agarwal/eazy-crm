package com.easycrm.platform.tenancy;

import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layer-3 guard: it fails when a tenant-scoped table is missing any part of its
 * Row-Level Security.
 *
 * <p>{@code TenantScopingArchTest} guards layer 2 — a new entity that forgets
 * {@code @TenantId} fails the build. Nothing guarded layer 3: a table that declared
 * {@code @TenantId} and omitted its two RLS lines passed the entire suite, because
 * Hibernate filtered it and every behavioural test still looked correct. It shipped
 * with one of the four isolation layers simply absent. This class is that missing
 * twin, and it keys off the same signal the database itself can see — the presence
 * of a {@code tenant_id} column — rather than off the Java annotation.
 *
 * <p><b>Why this asserts catalog state rather than behaviour.</b> The failure being
 * defended against is a connection made as the table owner, which Postgres exempts
 * from a table's own policies unless the table is FORCEd. It cannot be reproduced
 * here: Testcontainers creates the container's {@code owner} user as a superuser,
 * and superusers ignore FORCE regardless. So the assertion is on the three catalog
 * flags, and {@link #theGuardDetectsAnUnforcedTenantTable()} is what keeps that
 * assertion honest.
 */
class RlsCoverageIntegrationTest extends IntegrationTest {

    /**
     * Tables that carry a {@code tenant_id} column but are deliberately NOT
     * tenant-scoped. Mirrors {@code TenantScopingArchTest.GLOBAL_TABLES}; both lists
     * must be extended together, and only with review.
     *
     * <p>({@code tenant}, the third entry there, needs no exemption — it has no
     * {@code tenant_id} column at all, so the query below never sees it.)
     */
    private static final Set<String> GLOBAL_TABLES = Set.of(
        "refresh_token",   // pre-auth session table, looked up by hash
        "share_link",      // pre-auth share table: resolves the tenant itself
        "invitation"       // pre-auth invite table: resolves the tenant itself
    );

    /**
     * Every base table in {@code public} holding a live {@code tenant_id} column, with
     * the three facts that together make up layer 3: RLS enabled, RLS forced (so the
     * owner is bound by it too), and at least one policy to enforce.
     */
    private static final String TENANT_TABLE_RLS_STATE = """
        SELECT c.relname,
               c.relrowsecurity,
               c.relforcerowsecurity,
               (SELECT count(*) FROM pg_policy p WHERE p.polrelid = c.oid) AS policies
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND c.relkind = 'r'
           AND EXISTS (SELECT 1
                         FROM pg_attribute a
                        WHERE a.attrelid = c.oid
                          AND a.attname  = 'tenant_id'
                          AND NOT a.attisdropped)
         ORDER BY c.relname
        """;

    @Autowired DataSource dataSource; // app (non-owner) datasource; pg_catalog is world-readable

    @Test
    void everyTenantScopedTableHasRlsEnabledForcedAndPolicied() throws Exception {
        List<String> violations = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(TENANT_TABLE_RLS_STATE)) {
            while (rs.next()) {
                String table = rs.getString("relname");
                seen.add(table);
                if (GLOBAL_TABLES.contains(table)) continue;
                violations.addAll(describeViolations(
                    table, rs.getBoolean("relrowsecurity"),
                    rs.getBoolean("relforcerowsecurity"), rs.getInt("policies")));
            }
        }

        // Sanity: the query has to be finding the schema at all. Naming a table that has
        // existed since V9 distinguishes "everything passed" from "nothing was checked".
        assertTrue(seen.contains("product"),
            "guard query matched no known tenant table — it is not checking anything");
        assertEquals(List.of(), violations,
            "tenant-scoped tables are missing part of their Row-Level Security");

        // A stale exemption is as much a bug as a missing one: if a global table is
        // renamed or dropped, the allowlist should stop claiming to exempt it.
        for (String exempt : GLOBAL_TABLES) {
            assertTrue(seen.contains(exempt),
                "GLOBAL_TABLES exempts '" + exempt + "', which is not a tenant_id-bearing "
                    + "table any more — remove the stale entry");
        }
    }

    /**
     * Proves the guard above can actually go red. Without this, a query that silently
     * matched nothing — a typo in the catalog join, a schema rename — would read as a
     * clean pass forever.
     */
    @Test
    void theGuardDetectsAnUnforcedTenantTable() throws Exception {
        // DDL needs the owner: the app role has USAGE on the schema but no CREATE.
        try (Connection owner = ownerConnection(); Statement ddl = owner.createStatement()) {
            ddl.execute("CREATE TABLE rls_guard_probe (id UUID PRIMARY KEY, tenant_id UUID NOT NULL)");
            try {
                List<String> violations = new ArrayList<>();
                boolean probeSeen = false;

                try (Connection conn = dataSource.getConnection();
                     Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery(TENANT_TABLE_RLS_STATE)) {
                    while (rs.next()) {
                        if (!"rls_guard_probe".equals(rs.getString("relname"))) continue;
                        probeSeen = true;
                        violations.addAll(describeViolations(
                            "rls_guard_probe", rs.getBoolean("relrowsecurity"),
                            rs.getBoolean("relforcerowsecurity"), rs.getInt("policies")));
                    }
                }

                assertTrue(probeSeen, "the guard query did not even see the probe table");
                assertEquals(3, violations.size(),
                    "a tenant_id table with no RLS at all should trip all three checks, got: "
                        + violations);
            } finally {
                ddl.execute("DROP TABLE IF EXISTS rls_guard_probe");
            }
        }
    }

    private static List<String> describeViolations(
            String table, boolean rlsEnabled, boolean rlsForced, int policies) {
        List<String> violations = new ArrayList<>();
        if (!rlsEnabled) {
            violations.add(table + ": RLS not enabled (ALTER TABLE " + table
                + " ENABLE ROW LEVEL SECURITY)");
        }
        if (!rlsForced) {
            violations.add(table + ": RLS not forced, so the table owner bypasses it silently "
                + "(ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY)");
        }
        if (policies == 0) {
            violations.add(table + ": no policy — RLS with no policy denies everything, "
                + "which is a different bug, not isolation");
        }
        return violations;
    }
}
