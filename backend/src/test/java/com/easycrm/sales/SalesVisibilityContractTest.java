package com.easycrm.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.VisibilityContract;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * sales's half of the role→restriction contract. Reads an {@code Enquiry}, not a {@code
 * Quotation}: an Enquiry carries its own {@code assigned_to} so its visibility is intrinsic and
 * decided entirely by THIS class, whereas a Quotation's {@code viaCustomer} delegates the
 * ownership predicate to {@code crm.CustomerVisibility.spec()} — a quotation read would therefore
 * exercise crm's decision as much as (or instead of) this one, and could pass even while
 * SalesVisibility's own role comparison had drifted. See CustomerVisibilityContractTest for the
 * crm half.
 */
@SpringBootTest
class SalesVisibilityContractTest extends IntegrationTest {

    @Autowired
    SalesVisibility visibility;

    @Autowired
    EnquiryRepository enquiries;

    @Autowired
    TransactionTemplate tx;

    @Test
    void everyContractCaseHolds() {
        UUID tenantId = UUID.randomUUID();
        UUID me = UUID.randomUUID();
        UUID someoneElse = UUID.randomUUID();

        UUID theirs = TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, me, "OWNER"), () -> {
            Enquiry e = new Enquiry(
                    null,
                    "Test Contact",
                    "9876500001",
                    "9876500001",
                    null,
                    EnquirySource.MANUAL,
                    null,
                    someoneElse,
                    null);
            return enquiries.save(e).getId();
        });

        for (VisibilityContract.Case c : VisibilityContract.cases()) {
            boolean canSee = c.role() == null
                    ? seeWithNoPrincipal(tenantId, me, theirs)
                    : TenantContext.runAs(
                            new TenantContext.TenantPrincipal(tenantId, me, c.role()),
                            () -> visibility.findEnquiry(theirs).isPresent());

            assertThat(canSee).as("role=%s: %s", c.role(), c.why()).isEqualTo(!c.restricted());
        }
    }

    /**
     * See {@code CustomerVisibilityContractTest.seeWithNoPrincipal} for why this must bind a
     * principal, open a transaction through the injected {@link TransactionTemplate} while it is
     * still bound, and only then call {@code TenantContext.clear()} — clearing before the
     * transaction opens would let RLS hide the row for an unrelated reason (no tenant bound at
     * all) and the case would pass vacuously rather than exercising the fail-open ROLE decision.
     */
    private boolean seeWithNoPrincipal(UUID tenantId, UUID me, UUID id) {
        return TenantContext.runAs(
                new TenantContext.TenantPrincipal(tenantId, me, "SALES_EXEC"),
                () -> tx.execute(status -> {
                    TenantContext.clear();
                    return visibility.findEnquiry(id).isPresent();
                }));
    }
}
