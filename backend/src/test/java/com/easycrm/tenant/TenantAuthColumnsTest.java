package com.easycrm.tenant;

import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TenantAuthColumnsTest extends IntegrationTest {
    @Autowired TenantRepository tenants;

    @Test
    void persistsStatusAndTrialAndGstin() {
        Instant trial = Instant.parse("2026-08-01T00:00:00Z");
        Tenant t = new Tenant("gupta-trading", "Gupta Trading", "09",
            "09ABCDE1234F1Z5", TenantStatus.TRIAL, trial);
        tenants.save(t);

        Tenant loaded = tenants.findBySlug("gupta-trading").orElseThrow();
        assertEquals(TenantStatus.TRIAL, loaded.getStatus());
        assertEquals("09ABCDE1234F1Z5", loaded.getGstin());
        assertEquals(trial, loaded.getTrialEndsAt());
    }

    @Test
    void legacyThreeArgConstructorDefaultsToTrial() {
        Tenant t = new Tenant("no-frills", "No Frills", "27");
        tenants.save(t);
        assertEquals(TenantStatus.TRIAL, tenants.findBySlug("no-frills").orElseThrow().getStatus());
    }
}
