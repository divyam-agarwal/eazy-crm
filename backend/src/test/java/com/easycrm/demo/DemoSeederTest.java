package com.easycrm.demo;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.tenant.Tenant;
import com.easycrm.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DemoSeederTest extends IntegrationTest {
    @Autowired TenantRepository tenants;
    @Autowired DemoRecordRepository records;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void seedsTwoTenantsEachWithRecords() {
        new DemoSeeder(tenants, records).seed();

        Tenant a = tenants.findBySlug("alpha-traders").orElseThrow();
        Tenant b = tenants.findBySlug("bravo-distributors").orElseThrow();
        assertNotEquals(a.getId(), b.getId());

        TenantContext.set(new TenantContext.TenantPrincipal(a.getId(), UUID.randomUUID(), "OWNER"));
        assertFalse(records.findAll().isEmpty(), "tenant A has seeded records");
    }
}
