package com.easycrm.tenant;

import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.security.RoleGuard;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.tenant.web.dto.TenantProfileRequest;
import com.easycrm.tenant.web.dto.TenantResponse;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantService {

    private final TenantRepository tenants;
    private final RoleGuard roleGuard;

    public TenantService(TenantRepository tenants, RoleGuard roleGuard) {
        this.tenants = tenants;
        this.roleGuard = roleGuard;
    }

    @Transactional(readOnly = true)
    public TenantResponse get() {
        return TenantResponse.of(current());
    }

    @Transactional
    public TenantResponse updateProfile(TenantProfileRequest req) {
        roleGuard.requireOwner("only an owner may change the business profile");
        Tenant t = current();
        t.updateProfile(req.address(), req.phone(), req.email());
        return TenantResponse.of(t);
    }

    private Tenant current() {
        UUID id = TenantContext.tenantId();
        // `tenant` is a global table, so this is the one place the id must be passed
        // explicitly rather than left to @TenantId + RLS.
        return tenants.findById(id).orElseThrow(() -> new NotFoundException("tenant not found"));
    }
}
