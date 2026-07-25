package com.easycrm.platform.tenancy;

import com.easycrm.demo.DemoRecord;
import com.easycrm.demo.DemoRecordRepository;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TenantFilteringIntegrationTest extends IntegrationTest {
    @Autowired DemoRecordRepository records;

    @AfterEach void clear() { TenantContext.clear(); }

    private void asTenant(UUID t) {
        TenantContext.set(new TenantContext.TenantPrincipal(t, UUID.randomUUID(), "OWNER"));
    }

    @Test
    void tenantIdAutoPopulatedOnInsert() {
        UUID a = UUID.randomUUID();
        asTenant(a);
        DemoRecord saved = records.save(new DemoRecord("a-1"));
        assertEquals(a, saved.getTenantId(), "@TenantId auto-filled from context");
    }

    @Test
    void readsAreFilteredByTenant() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        asTenant(a); records.save(new DemoRecord("a-1"));
        asTenant(b); records.save(new DemoRecord("b-1"));

        asTenant(a);
        assertEquals(1, records.findAll().size(), "tenant A sees only its own row");
        assertEquals("a-1", records.findAll().get(0).getLabel());
    }
}
