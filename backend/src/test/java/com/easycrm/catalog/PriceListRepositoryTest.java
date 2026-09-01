package com.easycrm.catalog;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PriceListRepositoryTest extends IntegrationTest {
    @Autowired
    PriceListRepository priceLists;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void savesAndFindsByNameWithinTenant() {
        TenantContext.set(new TenantContext.TenantPrincipal(UUID.randomUUID(), UUID.randomUUID(), "OWNER"));
        priceLists.save(new PriceList("Dealer"));
        assertTrue(priceLists.findByName("Dealer").isPresent());
    }
}
