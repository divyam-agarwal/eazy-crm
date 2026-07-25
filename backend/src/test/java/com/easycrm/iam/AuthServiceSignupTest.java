package com.easycrm.iam;

import com.easycrm.iam.web.dto.AuthResponse;
import com.easycrm.iam.web.dto.SignupRequest;
import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.security.JwtService;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.tenant.Tenant;
import com.easycrm.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceSignupTest extends IntegrationTest {
    @Autowired AuthService auth;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;

    @AfterEach void clear() { TenantContext.clear(); }

    private SignupRequest req(String slug, String email) {
        return new SignupRequest(slug, "Acme Traders", "27", null, email, null, "hunter2pass");
    }

    @Test
    void createsTenantAndOwnerAtomicallyAndReturnsUsableToken() {
        AuthResponse res = auth.signup(req("acme", "owner@acme.test"));

        assertNotNull(res.accessToken());
        assertNotNull(res.refreshToken());
        assertEquals("OWNER", res.role());

        Tenant t = tenants.findBySlug("acme").orElseThrow();
        assertEquals(res.tenantId(), t.getId());

        // The access token resolves to the new tenant, and the owner exists within it.
        TenantContext.TenantPrincipal p = jwt.parse(res.accessToken());
        assertEquals(t.getId(), p.tenantId());
        TenantContext.set(p);
        assertTrue(users.findByEmail("owner@acme.test").isPresent());
    }

    @Test
    void duplicateSlugIsConflict() {
        auth.signup(req("dupe", "a@dupe.test"));
        assertThrows(ConflictException.class, () -> auth.signup(req("dupe", "b@dupe.test")));
    }
}
