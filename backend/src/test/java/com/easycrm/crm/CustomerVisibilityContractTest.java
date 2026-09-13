package com.easycrm.crm;

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
 * crm's half of the role→restriction contract. Asserts OBSERVABLE behaviour — can this principal
 * see a row owned by someone else — rather than calling a private predicate, so it would catch a
 * divergence introduced anywhere in the path, not only in the role comparison.
 */
@SpringBootTest
class CustomerVisibilityContractTest extends IntegrationTest {

    @Autowired
    CustomerVisibility visibility;

    @Autowired
    CustomerRepository customers;

    @Autowired
    TransactionTemplate tx;

    @Test
    void everyContractCaseHolds() {
        UUID tenantId = UUID.randomUUID();
        UUID me = UUID.randomUUID();
        UUID someoneElse = UUID.randomUUID();

        UUID theirs = TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, me, "OWNER"), () -> {
            Customer c = new Customer(
                    "contract-theirs", null, "27", null, null, 0, someoneElse, null, CustomerSource.MANUAL);
            return customers.save(c).getId();
        });

        for (VisibilityContract.Case c : VisibilityContract.cases()) {
            boolean canSee = c.role() == null
                    ? seeWithNoPrincipal(tenantId, me, theirs)
                    : TenantContext.runAs(
                            new TenantContext.TenantPrincipal(tenantId, me, c.role()),
                            () -> visibility.find(theirs).isPresent());

            assertThat(canSee).as("role=%s: %s", c.role(), c.why()).isEqualTo(!c.restricted());
        }
    }

    /**
     * The null-principal case is the one most likely to pass for the wrong reason. A naive
     * {@code TenantContext.clear()} BEFORE opening a transaction leaves no tenant bound either, so
     * RLS itself would hide {@code theirs} — the assertion would pass, but for an unrelated reason
     * (the tenant wall, not the role decision this test exists to pin).
     *
     * <p>So a principal is bound first and a transaction opened through the injected
     * {@link TransactionTemplate} WHILE it is still bound: {@code TenantAwareTransactionManager}
     * reads {@code TenantContext.tenantId()} once, in {@code doBegin}, to set the RLS GUC, and
     * Hibernate resolves the session's tenant once at session-open — neither is re-read afterward.
     * Only once the transaction (and therefore the tenant binding for this query) is locked in do
     * we call {@code TenantContext.clear()} to remove the ROLE, leaving the tenant wall intact and
     * isolating the fail-open branch this case is meant to exercise. Ported from the technique in
     * {@code CustomerVisibilityDirectTest.absentPrincipalIsUnrestrictedEvenThoughRlsStillScopesTheQuery}
     * and its sales twin.
     */
    private boolean seeWithNoPrincipal(UUID tenantId, UUID me, UUID id) {
        return TenantContext.runAs(
                new TenantContext.TenantPrincipal(tenantId, me, "SALES_EXEC"),
                () -> tx.execute(status -> {
                    TenantContext.clear();
                    return visibility.find(id).isPresent();
                }));
    }
}
