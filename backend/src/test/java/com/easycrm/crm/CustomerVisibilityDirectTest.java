package com.easycrm.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CustomerVisibility's own API, as distinct from CustomerVisibilityTest which exercises the same
 * rule through CustomerService. Both must pass: this one pins the seam sales now depends on.
 *
 * <p>The tests below the two original ones are ported from {@code VisibleFinderIntegrationTest}
 * and {@code VisibilityPolicyIntegrationTest} (pre-Wave-1.6), which exercised the same behaviour
 * against {@code VisibleFinder.findCustomer}/{@code pageCustomers} and {@code
 * VisibilityPolicy.customers()} respectively before those methods moved here. Ported rather than
 * rewritten, because the setup values (three customers: mine/execA, theirs/execB, unassigned) and
 * the paging test's page-size-1-over-2-rows construction are the contract, not incidental detail.
 */
@SpringBootTest
class CustomerVisibilityDirectTest extends IntegrationTest {

    @Autowired
    CustomerVisibility visibility;

    @Autowired
    CustomerRepository customers;

    @Autowired
    TransactionTemplate tx;

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

    // --- ported from VisibleFinderIntegrationTest / VisibilityPolicyIntegrationTest ------------
    //
    // These share one fixture (tenantId/execA/execB/mine/theirs/pool) because both source
    // classes' seed() built the identical three-customer shape under different field names
    // (mine/theirs/pool vs customerA/customerB/customerUnassigned) -- unified here rather than
    // duplicated.

    private final UUID tenantId = UUID.randomUUID();
    private final UUID execA = UUID.randomUUID();
    private final UUID execB = UUID.randomUUID();

    private UUID mine, theirs, pool;

    @BeforeEach
    void seedThreeCustomers() {
        run(execA, "OWNER", () -> {
            mine = save("Mine", execA);
            theirs = save("Theirs", execB);
            pool = save("Pool", null);
        });
    }

    @Test
    void byIdReturnsAVisibleRecord() {
        run(execA, "SALES_EXEC", () -> assertThat(visibility.find(mine)).isPresent());
    }

    @Test
    void byIdReturnsEmptyForAnInvisibleRecord() {
        run(execA, "SALES_EXEC", () -> assertThat(visibility.find(theirs)).isEmpty());
    }

    @Test
    void byIdReturnsAnUnassignedRecord() {
        run(execA, "SALES_EXEC", () -> assertThat(visibility.find(pool)).isPresent());
    }

    @Test
    void ownerSeesEvenAnotherExecsRecordById() {
        run(execA, "OWNER", () -> assertThat(visibility.find(theirs)).isPresent());
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
            var page = visibility.page(null, PageRequest.of(0, 1));
            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getTotalElements()).isEqualTo(2);
        });
    }

    /**
     * Ported from VisibilityPolicyIntegrationTest.ownerSeesEveryCustomer. The original also
     * asserted {@code assertThat(policy.unrestricted()).isTrue()} directly; {@code
     * CustomerVisibility.unrestricted()} is private by design (it is an implementation
     * detail behind {@code spec()}, not part of the class's public contract), so that line has no
     * direct equivalent here. The behaviour it certified -- OWNER sees every row -- is still
     * fully asserted below via the observable result of {@code spec()}.
     */
    @Test
    void ownerSeesEveryCustomer() {
        run(
                execA,
                "OWNER",
                () -> assertThat(ids(customers.findAll(visibility.spec())))
                        .containsExactlyInAnyOrder(mine, theirs, pool));
    }

    /** Ported from VisibilityPolicyIntegrationTest.salesManagerSeesEveryCustomer. See the note on
     *  {@code ownerSeesEveryCustomer} above re: {@code unrestricted()} no longer being public. */
    @Test
    void salesManagerSeesEveryCustomer() {
        run(
                execA,
                "SALES_MANAGER",
                () -> assertThat(ids(customers.findAll(visibility.spec()))).hasSize(3));
    }

    /** Ported from VisibilityPolicyIntegrationTest.salesExecSeesOwnAndUnassignedCustomersOnly. */
    @Test
    void salesExecSeesOwnAndUnassignedCustomersOnly() {
        run(
                execA,
                "SALES_EXEC",
                () -> assertThat(ids(customers.findAll(visibility.spec())))
                        .containsExactlyInAnyOrder(mine, pool)
                        .doesNotContain(theirs));
    }

    /**
     * Proves the fail-OPEN branch actually fires, not merely that a permissive role also comes
     * out unrestricted -- {@code unrestricted()}'s {@code .orElse(true)} only runs when {@code
     * TenantContext.get()} is truly empty, which a bound principal (of any role) never produces.
     *
     * <p>An absent principal is what internal flows with no user to attribute run under: async
     * listeners, {@code TenantJobRunner}'s synthetic {@code "SYSTEM"} principal, and tenant
     * provisioning. None of them carry a role to restrict, so defaulting to unrestricted is
     * correct -- the alternative (fail CLOSED) would silently hide rows from code that never
     * opted into any restriction in the first place. The tenant wall is unaffected either way:
     * RLS is a separate layer that does not depend on this decision.
     *
     * <p>Clearing {@code TenantContext} INSIDE an already-open transaction, rather than before
     * one starts, is deliberate: {@code TenantAwareTransactionManager} reads {@code
     * TenantContext.tenantId()} once at {@code doBegin} to set the RLS GUC, and Hibernate
     * resolves a session's tenant once at session-open -- neither is re-read afterward (see
     * {@code TenantContext.runAs}'s javadoc). So the query below still runs against execA's
     * tenant (RLS still applies), while the ROLE decision it makes is based on no principal at
     * all -- isolating the fail-open branch from the tenant wall it is not supposed to touch.
     */
    @Test
    void absentPrincipalIsUnrestrictedEvenThoughRlsStillScopesTheQuery() {
        TenantContext.runAs(
                new TenantContext.TenantPrincipal(tenantId, execA, "SALES_EXEC"),
                () -> tx.executeWithoutResult(status -> {
                    TenantContext.clear();
                    // theirs is execB's row -- invisible to execA under SALES_EXEC (see
                    // byIdReturnsEmptyForAnInvisibleRecord) whenever a principal IS bound. With
                    // none bound, it must be visible.
                    assertThat(visibility.find(theirs)).isPresent();
                }));
    }

    private void run(UUID userId, String role, Runnable body) {
        TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, userId, role), body);
    }

    private List<UUID> ids(List<Customer> rows) {
        return rows.stream().map(Customer::getId).toList();
    }

    private UUID save(String name, UUID assignedTo) {
        Customer c = new Customer(name, null, "27", null, null, 0, assignedTo, null, CustomerSource.MANUAL);
        return customers.saveAndFlush(c).getId();
    }
}
