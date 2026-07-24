package com.easycrm.platform.security;

import com.easycrm.platform.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {
    private final JwtService jwt = new JwtService(
        new JwtProperties("0123456789-0123456789-0123456789-secret", 900));

    @Test
    void mintThenParseRoundTrips() {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
        String token = jwt.mint(tenant, user, "OWNER");
        TenantContext.TenantPrincipal p = jwt.parse(token);
        assertEquals(tenant, p.tenantId());
        assertEquals(user, p.userId());
        assertEquals("OWNER", p.role());
    }

    @Test
    void tamperedTokenRejected() {
        String token = jwt.mint(UUID.randomUUID(), UUID.randomUUID(), "OWNER");
        assertThrows(RuntimeException.class, () -> jwt.parse(token + "x"));
    }
}
