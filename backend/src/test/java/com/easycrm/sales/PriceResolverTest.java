package com.easycrm.sales;

import com.easycrm.catalog.PriceList;
import com.easycrm.catalog.PriceListItem;
import com.easycrm.catalog.PriceListItemRepository;
import com.easycrm.catalog.PriceListRepository;
import com.easycrm.catalog.Product;
import com.easycrm.catalog.ProductRepository;
import com.easycrm.catalog.Uom;
import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.crm.CustomerSource;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PriceResolverTest extends IntegrationTest {
    @Autowired PriceResolver resolver;
    @Autowired ProductRepository products;
    @Autowired CustomerRepository customers;
    @Autowired PriceListRepository priceLists;
    @Autowired PriceListItemRepository priceListItems;
    @Autowired PlatformTransactionManager txManager;

    @AfterEach void clear() { TenantContext.clear(); }

    private void asTenant(UUID t) {
        TenantContext.set(new TenantContext.TenantPrincipal(t, UUID.randomUUID(), "OWNER"));
    }

    private Product newProduct(String sku, String base) {
        return new Product(sku, "Widget", "84818090", Uom.PCS, new BigDecimal("18.0000"), new BigDecimal(base));
    }

    private Customer newCustomer(UUID priceListId) {
        return new Customer("Acme", null, "27", null, null, 0, null, priceListId, CustomerSource.MANUAL);
    }

    @Test
    void fallsBackToBaseRateWhenNoPriceList() {
        asTenant(UUID.randomUUID());
        var tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> {
            Product p = products.save(newProduct("SKU-A", "100.00"));
            Customer c = customers.save(newCustomer(null));
            PriceResolver.Resolved r = resolver.resolve(c.getId(), p.getId());
            assertThat(r.rate()).isEqualByComparingTo("100.00");
            assertThat(r.name()).isEqualTo("Widget");
            assertThat(r.gstRate()).isEqualByComparingTo("18.0000");
        });
    }

    @Test
    void overrideRateWins() {
        asTenant(UUID.randomUUID());
        var tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> {
            Product p = products.save(newProduct("SKU-B", "100.00"));
            PriceList pl = priceLists.save(new PriceList("Dealer"));
            priceListItems.save(new PriceListItem(pl.getId(), p.getId(), new BigDecimal("80.00"), null));
            Customer c = customers.save(newCustomer(pl.getId()));
            assertThat(resolver.resolve(c.getId(), p.getId()).rate()).isEqualByComparingTo("80.00");
        });
    }

    @Test
    void discountPercentAppliesToBaseRate() {
        asTenant(UUID.randomUUID());
        var tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> {
            Product p = products.save(newProduct("SKU-C", "100.00"));
            PriceList pl = priceLists.save(new PriceList("Retail"));
            priceListItems.save(new PriceListItem(pl.getId(), p.getId(), null, new BigDecimal("10.0000")));
            Customer c = customers.save(newCustomer(pl.getId()));
            assertThat(resolver.resolve(c.getId(), p.getId()).rate()).isEqualByComparingTo("90.00");
        });
    }

    @Test
    void fallsBackWhenPriceListLacksProduct() {
        asTenant(UUID.randomUUID());
        var tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> {
            Product p = products.save(newProduct("SKU-D", "55.00"));
            PriceList pl = priceLists.save(new PriceList("Empty"));
            Customer c = customers.save(newCustomer(pl.getId()));
            assertThat(resolver.resolve(c.getId(), p.getId()).rate()).isEqualByComparingTo("55.00");
        });
    }
}
