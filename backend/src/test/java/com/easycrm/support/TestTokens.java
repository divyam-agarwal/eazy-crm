package com.easycrm.support;

import com.easycrm.platform.security.JwtService;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.tenant.Tenant;
import com.easycrm.tenant.TenantRepository;
import com.easycrm.tenant.TenantStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

@Component
public class TestTokens {
    @Autowired JwtService jwt;
    @Autowired TenantRepository tenants;
    @Autowired TransactionTemplate tx;

    public String owner(UUID tenantId) {
        return jwt.mint(tenantId, UUID.randomUUID(), "OWNER");
    }

    /**
     * A bearer token for an explicit principal. Visibility filtering keys on userId and
     * role, so a test that exercises it cannot use owner()/provisionOwner() — those mint a
     * random user id and always the unrestricted OWNER role.
     */
    public String as(UUID tenantId, UUID userId, String role) {
        return jwt.mint(tenantId, userId, role);
    }

    /**
     * Provision a REAL tenant row with the given GST state code and return an OWNER bearer
     * token bound to it. Quotation flows load the Tenant to read state_code for the
     * CGST/SGST-vs-IGST split, so a phantom tenant (owner(randomUUID)) is not enough.
     * Each call creates a distinct tenant.
     */
    public ProvisionedOwner provisionOwner(String stateCode) {
        String slug = "t-" + UUID.randomUUID().toString().substring(0, 8);
        Tenant tenant = new Tenant(slug, "Test Biz", stateCode);
        TenantContext.set(new TenantContext.TenantPrincipal(tenant.getId(), null, "SYSTEM"));
        try {
            tx.executeWithoutResult(s -> tenants.save(tenant));
        } finally {
            TenantContext.clear();
        }
        return new ProvisionedOwner(tenant.getId(),
            jwt.mint(tenant.getId(), UUID.randomUUID(), "OWNER"));
    }

    /**
     * Flip a provisioned tenant to SUSPENDED — the "stopped paying" state that
     * AuthService.login and InvitationService.requireLive both refuse to mint credentials
     * for. {@code tenant} is a GLOBAL table, so this needs a bound context only because
     * TenantAwareTransactionManager reads one at doBegin.
     */
    public void suspend(UUID tenantId) {
        TenantContext.set(new TenantContext.TenantPrincipal(tenantId, null, "SYSTEM"));
        try {
            tx.executeWithoutResult(s -> tenants.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("no such tenant: " + tenantId))
                .setStatus(TenantStatus.SUSPENDED));
        } finally {
            TenantContext.clear();
        }
    }

    public record ProvisionedOwner(UUID tenantId, String token) {}
}
