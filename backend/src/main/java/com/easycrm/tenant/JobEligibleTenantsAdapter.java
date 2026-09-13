package com.easycrm.tenant;

import com.easycrm.platform.job.JobEligibleTenants;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Supplies {@link JobEligibleTenants} from the tenant table. The TRIAL + ACTIVE decision moved here
 * from {@code TenantJobRunner.JOB_ELIGIBLE} along with its reason: SUSPENDED is excluded
 * deliberately (spec 2026-08-31 D4), so a suspended tenant is not swept.
 *
 * <p>Package-private: nothing should inject the adapter in preference to the port.
 */
@Component
class JobEligibleTenantsAdapter implements JobEligibleTenants {

    private static final List<TenantStatus> JOB_ELIGIBLE = List.of(TenantStatus.TRIAL, TenantStatus.ACTIVE);

    private final TenantRepository tenants;

    JobEligibleTenantsAdapter(TenantRepository tenants) {
        this.tenants = tenants;
    }

    @Override
    public List<UUID> ids() {
        return tenants.findByStatusIn(JOB_ELIGIBLE).stream().map(Tenant::getId).toList();
    }
}
