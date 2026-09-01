package com.easycrm.platform.security;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.platform.error.ForbiddenException;
import com.easycrm.platform.tenancy.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RoleGuardTest {

    private final RoleGuard guard = new RoleGuard();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void bind(String role) {
        TenantContext.set(new TenantContext.TenantPrincipal(UUID.randomUUID(), UUID.randomUUID(), role));
    }

    @Test
    void ownerPasses() {
        bind("OWNER");
        assertDoesNotThrow(() -> guard.requireOwner("nope"));
    }

    @Test
    void salesManagerIsRejected() {
        bind("SALES_MANAGER");
        ForbiddenException ex = assertThrows(ForbiddenException.class, () -> guard.requireOwner("nope"));
        assertEquals("nope", ex.getMessage(), "the caller's message must reach the 403 body");
    }

    @Test
    void salesExecIsRejected() {
        bind("SALES_EXEC");
        assertThrows(ForbiddenException.class, () -> guard.requireOwner("nope"));
    }

    // The unauthenticated case must be a 403 from the guard, not a NullPointerException.
    // TenantContext.get() returns Optional.empty() when nothing is bound.
    @Test
    void noPrincipalIsRejected() {
        assertThrows(ForbiddenException.class, () -> guard.requireOwner("nope"));
    }

    // A principal whose role is null (SYSTEM contexts built by AuthService pass a role
    // string, but nothing structurally prevents null) must not blow up the comparison.
    @Test
    void nullRoleIsRejected() {
        TenantContext.set(new TenantContext.TenantPrincipal(UUID.randomUUID(), UUID.randomUUID(), null));
        assertThrows(ForbiddenException.class, () -> guard.requireOwner("nope"));
    }
}
