package com.easycrm.tenant;

import com.easycrm.platform.error.ForbiddenException;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.tenant.web.dto.TenantProfileRequest;
import com.easycrm.tenant.web.dto.TenantResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class TenantService {

    private final TenantRepository tenants;

    public TenantService(TenantRepository tenants) { this.tenants = tenants; }

    @Transactional(readOnly = true)
    public TenantResponse get() {
        return TenantResponse.of(current());
    }

    @Transactional
    public TenantResponse updateProfile(TenantProfileRequest req) {
        requireOwner();
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

    private void requireOwner() {
        String role = TenantContext.get().map(TenantContext.TenantPrincipal::role).orElse(null);
        if (!"OWNER".equals(role)) {
            throw new ForbiddenException("only an owner may change the business profile");
        }
    }
}
