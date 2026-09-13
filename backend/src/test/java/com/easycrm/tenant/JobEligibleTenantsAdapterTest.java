package com.easycrm.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.easycrm.platform.job.JobEligibleTenants;
import com.easycrm.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The port's whole contract: TRIAL and ACTIVE tenants, SUSPENDED excluded (spec 2026-08-31 D4).
 * Tenant is a GLOBAL table -- no @TenantId, no RLS -- so this is legitimately readable with no
 * tenant context bound, which is the situation every scheduled job starts in.
 */
@SpringBootTest
class JobEligibleTenantsAdapterTest extends IntegrationTest {

    @Autowired
    JobEligibleTenants eligible;

    @Autowired
    TenantRepository tenants;

    private UUID create(String slug, TenantStatus status) {
        Tenant t = new Tenant(slug, "biz-" + slug, "27", null, status, null);
        return tenants.save(t).getId();
    }

    @Test
    void returnsTrialAndActiveButNotSuspended() {
        UUID trial = create("jet-trial", TenantStatus.TRIAL);
        UUID active = create("jet-active", TenantStatus.ACTIVE);
        UUID suspended = create("jet-suspended", TenantStatus.SUSPENDED);

        List<UUID> ids = eligible.ids();

        assertThat(ids).contains(trial, active);
        assertThat(ids)
                .as("a SUSPENDED tenant must not be swept -- spec 2026-08-31 D4")
                .doesNotContain(suspended);
    }
}
