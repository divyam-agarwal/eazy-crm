package com.easycrm.platform.visibility;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.crm.CustomerSource;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.sales.Order;
import com.easycrm.sales.OrderRepository;
import com.easycrm.sales.Quotation;
import com.easycrm.sales.QuotationRepository;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VisibilityPolicyIntegrationTest extends IntegrationTest {

    @Autowired VisibilityPolicy policy;
    @Autowired CustomerRepository customers;
    @Autowired QuotationRepository quotations;
    @Autowired OrderRepository orders;
    @Autowired TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID execA = UUID.randomUUID();
    private final UUID execB = UUID.randomUUID();

    private UUID customerA, customerB, customerUnassigned;
    private UUID quoteA, quoteB, quoteUnassigned;

    @BeforeEach
    void seed() {
        asPrincipal(execA, "OWNER", () -> {
            customerA = save(newCustomer("A Traders", execA)).getId();
            customerB = save(newCustomer("B Traders", execB)).getId();
            customerUnassigned = save(newCustomer("Pool Traders", null)).getId();
            quoteA = saveQuote(customerA);
            quoteB = saveQuote(customerB);
            quoteUnassigned = saveQuote(customerUnassigned);
        });
    }

    @Test
    void ownerSeesEveryCustomer() {
        asPrincipal(execA, "OWNER", () -> {
            assertThat(policy.unrestricted()).isTrue();
            assertThat(ids(customers.findAll(policy.customers())))
                .containsExactlyInAnyOrder(customerA, customerB, customerUnassigned);
        });
    }

    @Test
    void salesManagerSeesEveryCustomer() {
        asPrincipal(execA, "SALES_MANAGER", () -> {
            assertThat(policy.unrestricted()).isTrue();
            assertThat(ids(customers.findAll(policy.customers()))).hasSize(3);
        });
    }

    @Test
    void salesExecSeesOwnAndUnassignedCustomersOnly() {
        asPrincipal(execA, "SALES_EXEC", () -> {
            assertThat(policy.unrestricted()).isFalse();
            assertThat(ids(customers.findAll(policy.customers())))
                .containsExactlyInAnyOrder(customerA, customerUnassigned)
                .doesNotContain(customerB);
        });
    }

    @Test
    void salesExecSeesQuotationsThroughTheirCustomer() {
        asPrincipal(execA, "SALES_EXEC", () ->
            assertThat(ids(quotations.findAll(policy.quotations())))
                .containsExactlyInAnyOrder(quoteA, quoteUnassigned)
                .doesNotContain(quoteB));
    }

    @Test
    void salesExecSeesOrdersThroughTheirCustomer() {
        UUID orderA = asPrincipalGet(execA, "OWNER", () -> saveOrder(customerA, quoteA));
        UUID orderB = asPrincipalGet(execA, "OWNER", () -> saveOrder(customerB, quoteB));

        asPrincipal(execA, "SALES_EXEC", () ->
            assertThat(ids(orders.findAll(policy.orders())))
                .contains(orderA)
                .doesNotContain(orderB));
    }

    /** A principal with no user id at all must not be silently restricted to nothing. */
    @Test
    void absentPrincipalIsUnrestricted() {
        TenantContext.clear();
        assertThat(policy.unrestricted()).isTrue();
    }

    // --- helpers -------------------------------------------------------------

    private void asPrincipal(UUID userId, String role, Runnable body) {
        TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, userId, role),
            () -> tx.executeWithoutResult(s -> body.run()));
    }

    private <T> T asPrincipalGet(UUID userId, String role, java.util.function.Supplier<T> body) {
        return TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, userId, role),
            () -> tx.execute(s -> body.get()));
    }

    private Customer newCustomer(String name, UUID assignedTo) {
        return new Customer(name, null, "27", "addr", "addr", 0,
            assignedTo, null, CustomerSource.MANUAL);
    }

    private Customer save(Customer c) { return customers.save(c); }

    private UUID saveQuote(UUID customerId) {
        return quotations.save(new Quotation(customerId, null)).getId();
    }

    private UUID saveOrder(UUID customerId, UUID quotationId) {
        return orders.save(new Order(quotationId, UUID.randomUUID(), customerId,
            "SO-" + UUID.randomUUID().toString().substring(0, 8),
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            null, LocalDate.now())).getId();
    }

    private static List<UUID> ids(List<? extends com.easycrm.platform.persistence.BaseEntity> rows) {
        return rows.stream().map(com.easycrm.platform.persistence.BaseEntity::getId).toList();
    }
}
