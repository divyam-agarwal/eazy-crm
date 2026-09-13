package com.easycrm.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.easycrm.iam.Role;
import com.easycrm.support.VisibilityContract;
import org.junit.jupiter.api.Test;

/**
 * crm compares the role as a String on purpose — TenantPrincipal.role is a String, and depending on
 * iam for the Role type would put a compile edge on what becomes a separate deployable (spec §3.2,
 * and RoleGuard's javadoc for the original argument). The cost is that renaming a role would not
 * fail compilation. This test pays it: TEST scope may depend on anything, so it holds both sides
 * together without creating the production edge.
 *
 * <p><b>Why {@code itIsTheSharedRestrictedRoleTier} anchors against {@code
 * VisibilityContract.RESTRICTED_ROLE} rather than {@code sales.SalesVisibility.RESTRICTED_ROLE}
 * directly:</b> both constants are package-private by design (crm and sales must not share
 * production code), so no single test class can see both to compare them directly. Anchoring each
 * side independently to the SAME shared test-scope literal is the equivalent assertion: if either
 * production constant drifts — including to a different value that is STILL a real {@link Role}
 * name, which {@code theRestrictedRoleLiteralIsARealRole} alone would not catch — the test on that
 * drifted side goes red. That matters because of an asymmetric failure mode found in this slice's
 * review: {@code sales.SalesVisibility.viaCustomer} asks {@code crm.CustomerVisibility.spec()} for
 * the Customer predicate rather than restating it, so if the two {@code RESTRICTED_ROLE} literals
 * ever disagreed — e.g. crm classifies a role as unrestricted while sales classifies the SAME role
 * as restricted — {@code viaCustomer}'s "restricted" branch would still build its subquery, but
 * {@code customerVisibility.spec()} would hand back the always-true predicate, so the EXISTS
 * degrades to a bare {@code EXISTS (SELECT id FROM customer WHERE id = q.customer_id)} — a pure
 * existence check. A SALES_EXEC would then see EVERY quotation whose customer row exists,
 * regardless of who it is assigned to: a silent widening, not a silent narrowing, which is why it
 * is dangerous and would not be noticed by manual testing. The same hazard applies to hazard H6:
 * implementing the SALES_MANAGER middle tier in {@code crm.CustomerVisibility} alone, without the
 * matching edit in {@code sales.SalesVisibility}, would reopen exactly this gap for every
 * Quotation and Order read.
 */
class CustomerVisibilityRoleLiteralTest {

    @Test
    void theRestrictedRoleLiteralIsARealRole() {
        assertThatCode(() -> Role.valueOf(CustomerVisibility.RESTRICTED_ROLE))
                .as(
                        "%s is not a Role constant -- a role was renamed and a String comparison "
                                + "silently stopped matching",
                        CustomerVisibility.RESTRICTED_ROLE)
                .doesNotThrowAnyException();
    }

    @Test
    void itIsTheSharedRestrictedRoleTier() {
        assertThat(CustomerVisibility.RESTRICTED_ROLE).isEqualTo(VisibilityContract.RESTRICTED_ROLE);
    }
}
