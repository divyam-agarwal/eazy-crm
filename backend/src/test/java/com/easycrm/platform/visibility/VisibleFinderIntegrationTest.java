package com.easycrm.platform.visibility;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.crm.CustomerSource;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VisibleFinderIntegrationTest extends IntegrationTest {

    @Autowired VisibleFinder finder;
    @Autowired CustomerRepository customers;
    @Autowired TransactionTemplate tx;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID execA = UUID.randomUUID();
    private final UUID execB = UUID.randomUUID();

    private UUID mine, theirs, pool;

    @BeforeEach
    void seed() {
        run(execA, "OWNER", () -> {
            mine   = customers.save(customer("Mine", execA)).getId();
            theirs = customers.save(customer("Theirs", execB)).getId();
            pool   = customers.save(customer("Pool", null)).getId();
        });
    }

    @Test
    void byIdReturnsAVisibleRecord() {
        run(execA, "SALES_EXEC", () ->
            assertThat(finder.findCustomer(mine)).isPresent());
    }

    @Test
    void byIdReturnsEmptyForAnInvisibleRecord() {
        run(execA, "SALES_EXEC", () ->
            assertThat(finder.findCustomer(theirs)).isEmpty());
    }

    @Test
    void byIdReturnsAnUnassignedRecord() {
        run(execA, "SALES_EXEC", () ->
            assertThat(finder.findCustomer(pool)).isPresent());
    }

    @Test
    void ownerSeesEvenAnotherExecsRecordById() {
        run(execA, "OWNER", () ->
            assertThat(finder.findCustomer(theirs)).isPresent());
    }

    /** The paging path builds a COUNT query too — the subquery must survive both. */
    @Test
    void pagingAppliesVisibilityToBothTheDataAndCountQueries() {
        run(execA, "SALES_EXEC", () -> {
            var page = finder.pageCustomers(null, PageRequest.of(0, 50));
            assertThat(page.getContent()).extracting(Customer::getId)
                .containsExactlyInAnyOrder(mine, pool);
            assertThat(page.getTotalElements()).isEqualTo(2);
        });
    }

    private void run(UUID userId, String role, Runnable body) {
        TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, userId, role),
            () -> tx.executeWithoutResult(s -> body.run()));
    }

    private Customer customer(String name, UUID assignedTo) {
        return new Customer(name, null, "27", "addr", "addr", 0,
            assignedTo, null, CustomerSource.MANUAL);
    }
}
