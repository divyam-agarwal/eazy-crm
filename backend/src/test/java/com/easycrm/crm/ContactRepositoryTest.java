package com.easycrm.crm;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ContactRepositoryTest extends IntegrationTest {
    @Autowired ContactRepository contacts;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void findsContactsByCustomerWithinTenant() {
        TenantContext.set(new TenantContext.TenantPrincipal(UUID.randomUUID(), UUID.randomUUID(), "OWNER"));
        UUID customerId = UUID.randomUUID();
        contacts.save(new Contact(customerId, "Ravi", "9876543210", "9876543210",
                                  "ravi@acme.test", "Purchase", true));
        assertEquals(1, contacts.findByCustomerId(customerId).size());
        assertTrue(contacts.findByCustomerId(UUID.randomUUID()).isEmpty());
    }
}
