package com.easycrm.iam;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RoleTest {
    @Test
    void rolesExist() {
        assertEquals(3, Role.values().length);
        assertNotNull(Role.valueOf("OWNER"));
        assertNotNull(Role.valueOf("SALES_MANAGER"));
        assertNotNull(Role.valueOf("SALES_EXEC"));
    }

    @Test
    void userStatusesExist() {
        assertNotNull(UserStatus.valueOf("ACTIVE"));
        assertNotNull(UserStatus.valueOf("DISABLED"));
    }
}
