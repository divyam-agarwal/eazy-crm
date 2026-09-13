package com.easycrm.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

/**
 * CustomerVisibility's own API, as distinct from CustomerVisibilityTest which exercises the same
 * rule through CustomerService. Both must pass: this one pins the seam sales now depends on.
 */
@SpringBootTest
class CustomerVisibilityDirectTest extends IntegrationTest {

    @Autowired
    CustomerVisibility visibility;

    @Autowired
    CustomerRepository customers;

    @Test
    void salesExecSeesOwnedAndUnassignedButNotSomeoneElses() {
        UUID tenantId = UUID.randomUUID();
        UUID me = UUID.randomUUID();
        UUID someoneElse = UUID.randomUUID();

        TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, me, "OWNER"), () -> {
            UUID mine = save("mine", me);
            UUID unassigned = save("unassigned", null);
            UUID theirs = save("theirs", someoneElse);

            TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, me, "SALES_EXEC"), () -> {
                assertThat(visibility.find(mine)).isPresent();
                assertThat(visibility.find(unassigned)).isPresent();
                assertThat(visibility.find(theirs)).isEmpty();
                assertThat(visibility.isVisible(theirs)).isFalse();
            });

            // OWNER is unrestricted and sees all three.
            assertThat(visibility.find(theirs)).isPresent();
        });
    }

    @Test
    void aNullFilterIsAnUnfilteredPage() {
        UUID tenantId = UUID.randomUUID();
        TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, UUID.randomUUID(), "OWNER"), () -> {
            save("nullfilter", null);
            // Specification.and(null) throws in this Spring Data version; page() must guard it.
            assertThat(visibility.page(null, PageRequest.of(0, 10))).isNotNull();
        });
    }

    private UUID save(String name, UUID assignedTo) {
        Customer c = new Customer(name, null, "27", null, null, 0, assignedTo, null, CustomerSource.MANUAL);
        return customers.saveAndFlush(c).getId();
    }
}
