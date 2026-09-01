package com.easycrm.crm;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CustomerRepositoryTest extends IntegrationTest {
    @Autowired
    CustomerRepository customers;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void asTenant(UUID t) {
        TenantContext.set(new TenantContext.TenantPrincipal(t, UUID.randomUUID(), "OWNER"));
    }

    @Test
    void savesAndFindsByGstinWithinTenant() {
        asTenant(UUID.randomUUID());
        customers.save(new Customer(
                "Acme Traders", "27AAPFU0939F1ZV", "27", null, null, 30, null, null, CustomerSource.MANUAL));
        assertTrue(customers.findByGstin("27AAPFU0939F1ZV").isPresent());
    }

    @Test
    void allowsMultipleCustomersWithoutGstin() {
        asTenant(UUID.randomUUID());
        customers.save(new Customer("Walk-in A", null, "27", null, null, 0, null, null, CustomerSource.PHONE));
        customers.save(new Customer("Walk-in B", null, "27", null, null, 0, null, null, CustomerSource.PHONE));
        assertEquals(2, customers.findAll().size(), "null GSTINs must not collide on the unique key");
    }
}
