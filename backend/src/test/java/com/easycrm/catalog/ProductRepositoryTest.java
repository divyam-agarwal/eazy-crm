package com.easycrm.catalog;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProductRepositoryTest extends IntegrationTest {
    @Autowired ProductRepository products;

    @AfterEach void clear() { TenantContext.clear(); }

    private void asTenant(UUID t) {
        TenantContext.set(new TenantContext.TenantPrincipal(t, UUID.randomUUID(), "OWNER"));
    }

    private Product sample(String sku) {
        return new Product(sku, "Widget", "84818090", Uom.PCS,
                           new BigDecimal("18.0000"), new BigDecimal("100.00"));
    }

    @Test
    void savesAndFindsBySkuWithinTenant() {
        asTenant(UUID.randomUUID());
        products.save(sample("SKU-1"));
        assertTrue(products.findBySku("SKU-1").isPresent());
    }

    @Test
    void findBySkuIsTenantScoped() {
        asTenant(UUID.randomUUID());
        products.save(sample("SKU-DUP"));
        asTenant(UUID.randomUUID()); // different tenant
        assertTrue(products.findBySku("SKU-DUP").isEmpty(), "sku lookup must not cross tenants");
    }
}
