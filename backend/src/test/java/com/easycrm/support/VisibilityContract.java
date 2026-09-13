package com.easycrm.support;

import com.easycrm.iam.Role;
import java.util.List;

/**
 * The role→restriction contract every visibility interpreter must satisfy.
 *
 * <p>Wave 1.6 deleted the single shared {@code unrestricted()} method, because post-split
 * crm and sales are separate deployables that cannot share a decision (spec §3.2). This class is
 * what replaces it: a shared contract plus independent implementations plus conformance tests —
 * the discipline you would use across services. It lives in TEST scope, so sharing it creates no
 * production dependency between the packages.
 *
 * <p><b>H6 will edit this list.</b> When SALES_MANAGER becomes a restricted middle tier, its
 * expectation changes here once and both interpreters' tests go red until both are updated. That
 * is the entire reason this file exists: a divergence between the two interpreters fails OPEN,
 * showing rows to someone who should not see them, which is the direction that does not announce
 * itself.
 */
public final class VisibilityContract {

    private VisibilityContract() {}

    /**
     * The single source of truth {@code CustomerVisibility.RESTRICTED_ROLE} and
     * {@code SalesVisibility.RESTRICTED_ROLE} are each independently pinned against, in
     * {@code CustomerVisibilityRoleLiteralTest} and {@code SalesVisibilityRoleLiteralTest}.
     *
     * <p>Those two production constants are package-private on purpose (crm and sales must not
     * share production code — that IS the design this slice landed), so no single test class can
     * read both and assert {@code CustomerVisibility.RESTRICTED_ROLE.equals(SalesVisibility
     * .RESTRICTED_ROLE)} directly. Two tests each asserting their own constant against this one
     * shared literal is the equivalent: if either production constant drifts to a different value
     * — including another value that is still a real {@link Role} name, which a test that only
     * checks {@code Role.valueOf(...)} does not throw would miss — the test on that side goes red.
     * Checking each side merely "is some valid Role name" independently is exactly the assertion
     * that would keep passing while the two diverged from EACH OTHER; anchoring both to this one
     * constant is what rules that out.
     */
    public static final String RESTRICTED_ROLE = Role.SALES_EXEC.name();

    /** A role as it appears in the JWT claim, and whether that principal is restricted. */
    public record Case(String role, boolean restricted, String why) {}

    public static List<Case> cases() {
        return List.of(
                new Case("OWNER", false, "an owner sees every row in their tenant"),
                new Case(
                        "SALES_MANAGER",
                        false,
                        "collapsed into the unrestricted tier today -- H6 is the unbuilt three-tier "
                                + "rule, and when it lands this expectation flips in both interpreters"),
                new Case("SALES_EXEC", true, "the one restricted role"),
                new Case(
                        "SYSTEM",
                        false,
                        "the synthetic principal TenantJobRunner and AuthService bind; a job must "
                                + "see the whole tenant"),
                new Case(
                        "NOT_A_REAL_ROLE",
                        false,
                        "fail-OPEN on an unrecognised role. Deliberate: a role added later must not "
                                + "silently hide rows from users who could see them the day before. "
                                + "This is also why the comparison is a String and not Role.valueOf, "
                                + "which would throw here"),
                new Case(
                        null,
                        false,
                        "no principal bound at all -- async listeners and tenant provisioning. "
                                + "TenantContext.get() is empty and the default is unrestricted"));
    }
}
