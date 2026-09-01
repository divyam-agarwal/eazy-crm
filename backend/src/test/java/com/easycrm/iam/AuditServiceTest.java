package com.easycrm.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AuditServiceTest extends IntegrationTest {
    @Autowired
    AuditService audit;

    @Autowired
    AuditLogRepository logs;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void recordsUnderTenantContext() {
        UUID tenant = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(tenant, actor, "OWNER"));

        audit.record("LOGIN_SUCCESS", actor, Map.of("ip", "1.2.3.4"));

        assertEquals(1, logs.countByAction("LOGIN_SUCCESS"));
    }

    @Test
    void auditIsTenantScoped() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(a, UUID.randomUUID(), "OWNER"));
        audit.record("SIGNUP", null, Map.of());

        TenantContext.set(new TenantContext.TenantPrincipal(b, UUID.randomUUID(), "OWNER"));
        assertEquals(0, logs.countByAction("SIGNUP"), "tenant B cannot see A's audit rows");
    }
}
