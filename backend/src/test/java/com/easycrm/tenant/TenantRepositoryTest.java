package com.easycrm.tenant;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TenantRepositoryTest extends IntegrationTest {
    @Autowired
    TenantRepository tenants;

    @Test
    void savesAndFindsBySlug() {
        Tenant t = new Tenant("acme-traders", "Acme Traders", "27");
        tenants.save(t);
        assertNotNull(t.getId());
        assertTrue(tenants.findBySlug("acme-traders").isPresent());
    }
}
