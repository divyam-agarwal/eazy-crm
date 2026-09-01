package com.easycrm.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.platform.tenancy.TenantContext.TenantPrincipal;
import com.easycrm.support.IntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class OrderRepositoryTest extends IntegrationTest {

    @Autowired
    OrderRepository orders;

    @Autowired
    TransactionTemplate tx;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private Order newOrder(UUID quotationId, String orderNo) {
        return new Order(
                quotationId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                orderNo,
                new BigDecimal("100.00"),
                new BigDecimal("18.00"),
                new BigDecimal("118.00"),
                "PO-1",
                LocalDate.of(2026, 7, 27));
    }

    @Test
    void persistsAndReadsBackWithinTenant() {
        UUID tenant = UUID.randomUUID();
        UUID quotationId = UUID.randomUUID();
        TenantContext.set(new TenantPrincipal(tenant, null, "OWNER"));
        UUID id = tx.execute(
                s -> orders.save(newOrder(quotationId, "ORD/25-26/0001")).getId());

        Order found = tx.execute(s -> orders.findByQuotationId(quotationId).orElseThrow());
        assertThat(found.getId()).isEqualTo(id);
        assertThat(found.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(found.getGrandTotal()).isEqualByComparingTo("118.00");
    }

    @Test
    void oneOrderPerQuotationIsEnforced() {
        UUID tenant = UUID.randomUUID();
        UUID quotationId = UUID.randomUUID();
        TenantContext.set(new TenantPrincipal(tenant, null, "OWNER"));
        tx.executeWithoutResult(s -> orders.save(newOrder(quotationId, "ORD/25-26/0001")));

        assertThatThrownBy(() ->
                        tx.executeWithoutResult(s -> orders.saveAndFlush(newOrder(quotationId, "ORD/25-26/0002"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rlsHidesAnotherTenantsOrder() {
        UUID tenantA = UUID.randomUUID();
        UUID quotationId = UUID.randomUUID();
        TenantContext.set(new TenantPrincipal(tenantA, null, "OWNER"));
        tx.executeWithoutResult(s -> orders.save(newOrder(quotationId, "ORD/25-26/0001")));

        TenantContext.set(new TenantPrincipal(UUID.randomUUID(), null, "OWNER"));
        Optional<Order> result = tx.execute(s -> orders.findByQuotationId(quotationId));
        assertThat(result).isEmpty();
    }
}
