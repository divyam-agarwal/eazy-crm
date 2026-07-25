package com.easycrm.platform.tenancy;

import com.easycrm.iam.Role;
import com.easycrm.iam.User;
import com.easycrm.iam.UserRepository;
import com.easycrm.iam.UserStatus;
import com.easycrm.support.IntegrationTest;
import com.easycrm.tenant.Tenant;
import com.easycrm.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the atomic-provisioning mechanism used by signup: because a Hibernate session
 * resolves its tenant at session-open (and never re-reads it), the tenant context must be
 * set BEFORE the transaction opens. The Tenant carries an application-assigned UUIDv7 id, so
 * it is known up front — the tenant and its first tenant-scoped row then insert atomically
 * in one transaction, with @TenantId + RLS both satisfied. See engineering-challenges #9.
 */
class TenantProvisioningTest extends IntegrationTest {
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired TransactionTemplate tx;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void insertsTenantAndItsFirstTenantScopedRowAtomically() {
        // Tenant id is assigned in the constructor, before any transaction/session opens.
        Tenant tenant = new Tenant("brand-new", "Brand New", "27");
        TenantContext.set(new TenantContext.TenantPrincipal(tenant.getId(), null, "SYSTEM"));

        User owner = tx.execute(status -> {
            tenants.save(tenant);
            return users.save(new User("o@brand-new.test", null, "hash", Role.OWNER, UserStatus.ACTIVE));
        });

        assertEquals(tenant.getId(), owner.getTenantId(), "@TenantId filled from context set before session open");
        assertTrue(tenants.findBySlug("brand-new").isPresent(), "tenant committed");
        assertTrue(users.findByEmail("o@brand-new.test").isPresent(), "owner committed under its tenant");
    }
}
