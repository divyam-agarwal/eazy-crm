package com.easycrm.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class UserRepositoryTest extends IntegrationTest {
    @Autowired
    UserRepository users;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void asTenant(UUID t) {
        TenantContext.set(new TenantContext.TenantPrincipal(t, UUID.randomUUID(), "OWNER"));
    }

    @Test
    void savesAndFindsByEmailWithinTenant() {
        UUID a = UUID.randomUUID();
        asTenant(a);
        users.save(new User("owner@acme.test", null, "hash", Role.OWNER, UserStatus.ACTIVE));

        assertTrue(users.findByEmail("owner@acme.test").isPresent());
    }

    @Test
    void findByEmailIsTenantScoped() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        asTenant(a);
        users.save(new User("dup@x.test", null, "hash", Role.OWNER, UserStatus.ACTIVE));

        asTenant(b); // tenant B has no such user
        assertTrue(users.findByEmail("dup@x.test").isEmpty(), "email lookup must not cross tenants");
    }
}
