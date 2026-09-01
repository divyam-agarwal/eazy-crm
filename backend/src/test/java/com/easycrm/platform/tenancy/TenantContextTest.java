package com.easycrm.platform.tenancy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextTest {
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void unsetByDefault() {
        assertTrue(TenantContext.get().isEmpty());
        assertNull(TenantContext.tenantId());
    }

    @Test
    void setAndGet() {
        UUID t = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(t, UUID.randomUUID(), "OWNER"));
        assertEquals(t, TenantContext.tenantId());
        TenantContext.clear();
        assertNull(TenantContext.tenantId());
    }

    @Test
    void runAsRestoresPrevious() {
        UUID outer = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(outer, UUID.randomUUID(), "OWNER"));
        UUID inner = UUID.randomUUID();
        TenantContext.runAs(
                new TenantContext.TenantPrincipal(inner, UUID.randomUUID(), "SALES_EXEC"),
                () -> assertEquals(inner, TenantContext.tenantId()));
        assertEquals(outer, TenantContext.tenantId(), "previous context restored after runAs");
    }
}
