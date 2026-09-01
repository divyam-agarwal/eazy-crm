package com.easycrm.platform.visibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.crm.CustomerSource;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.sales.Order;
import com.easycrm.sales.OrderRepository;
import com.easycrm.sales.Quotation;
import com.easycrm.sales.QuotationRepository;
import com.easycrm.support.IntegrationTest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

class VisibleFinderIntegrationTest extends IntegrationTest {

    @Autowired
    VisibleFinder finder;

    @Autowired
    CustomerRepository customers;

    @Autowired
    QuotationRepository quotations;

    @Autowired
    OrderRepository orders;

    @Autowired
    TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID execA = UUID.randomUUID();
    private final UUID execB = UUID.randomUUID();

    private UUID mine, theirs, pool;

    @BeforeEach
    void seed() {
        run(execA, "OWNER", () -> {
            mine = customers.save(customer("Mine", execA)).getId();
            theirs = customers.save(customer("Theirs", execB)).getId();
            pool = customers.save(customer("Pool", null)).getId();

            for (UUID customerId : new UUID[] {mine, theirs, pool}) {
                UUID quotationId =
                        quotations.save(new Quotation(customerId, null)).getId();
                orders.save(order(customerId, quotationId));
            }
        });
    }

    @Test
    void byIdReturnsAVisibleRecord() {
        run(execA, "SALES_EXEC", () -> assertThat(finder.findCustomer(mine)).isPresent());
    }

    @Test
    void byIdReturnsEmptyForAnInvisibleRecord() {
        run(execA, "SALES_EXEC", () -> assertThat(finder.findCustomer(theirs)).isEmpty());
    }

    @Test
    void byIdReturnsAnUnassignedRecord() {
        run(execA, "SALES_EXEC", () -> assertThat(finder.findCustomer(pool)).isPresent());
    }

    @Test
    void ownerSeesEvenAnotherExecsRecordById() {
        run(execA, "OWNER", () -> assertThat(finder.findCustomer(theirs)).isPresent());
    }

    /**
     * The paging path builds a COUNT query too, and {@code PageableExecutionUtils} only
     * executes it when the content page is not already known to be complete: it
     * short-circuits whenever {@code offset == 0 && pageSize > content.size()}. A page
     * size of 50 over 2 visible rows would hit that short-circuit and never run the count
     * query at all, so this uses page size 1 -- {@code content.size() == 1 == pageSize}
     * fails the short-circuit's strict {@code >}, forcing the real COUNT(*) to execute --
     * to prove the visibility filter actually survives translation into a count query.
     */
    @Test
    void pagingAppliesVisibilityToBothTheDataAndCountQueries() {
        run(execA, "SALES_EXEC", () -> {
            var page = finder.pageCustomers(null, PageRequest.of(0, 1));
            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getTotalElements()).isEqualTo(2);
        });
    }

    /**
     * Quotations don't carry their own assigned_to -- visibility is derived from the
     * customer via an EXISTS subquery (VisibilityPolicy.viaCustomer). The count query is
     * the case an earlier review flagged as untested: forcing PageableExecutionUtils to
     * actually run COUNT(*) here proves the subquery form survives that translation, not
     * only the data query's.
     */
    @Test
    void pagingAppliesVisibilityToTheQuotationCountQuery() {
        run(execA, "SALES_EXEC", () -> {
            var page = finder.pageQuotations(null, PageRequest.of(0, 1));
            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getTotalElements()).isEqualTo(2);
        });
    }

    /** Same reasoning as the quotation case above, for orders. */
    @Test
    void pagingAppliesVisibilityToTheOrderCountQuery() {
        run(execA, "SALES_EXEC", () -> {
            var page = finder.pageOrders(null, PageRequest.of(0, 1));
            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getTotalElements()).isEqualTo(2);
        });
    }

    private void run(UUID userId, String role, Runnable body) {
        TenantContext.runAs(
                new TenantContext.TenantPrincipal(tenantId, userId, role),
                () -> tx.executeWithoutResult(s -> body.run()));
    }

    private Customer customer(String name, UUID assignedTo) {
        return new Customer(name, null, "27", "addr", "addr", 0, assignedTo, null, CustomerSource.MANUAL);
    }

    private Order order(UUID customerId, UUID quotationId) {
        return new Order(
                quotationId,
                UUID.randomUUID(),
                customerId,
                "SO-" + UUID.randomUUID().toString().substring(0, 8),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                null);
    }
}
