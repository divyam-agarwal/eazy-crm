package com.easycrm.catalog;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PriceListItemRepositoryTest extends IntegrationTest {
    @Autowired PriceListItemRepository items;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void savesAndFindsItemsByPriceList() {
        TenantContext.set(new TenantContext.TenantPrincipal(UUID.randomUUID(), UUID.randomUUID(), "OWNER"));
        UUID priceListId = UUID.randomUUID();
        items.save(new PriceListItem(priceListId, UUID.randomUUID(), new BigDecimal("95.00"), null));
        assertEquals(1, items.findByPriceListId(priceListId).size());
    }
}
