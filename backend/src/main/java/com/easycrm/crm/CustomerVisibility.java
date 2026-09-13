package com.easycrm.crm;

import com.easycrm.platform.tenancy.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * The ONLY class permitted to read CustomerRepository. VisibilityScopingArchTest fails the build
 * on any other caller outside its allowlist — see spec 2026-08-29-record-visibility-design.md §8
 * and spec 2026-09-13-wave-1.6-module-boundaries-design.md §4.4 for why that rule became
 * per-repository. CustomerService keeps the repository for save() and the two uniqueness
 * pre-checks, and nothing else.
 *
 * <p>This lived in {@code platform.visibility.VisibleFinder} until Wave 1.6. It could not be
 * inverted behind a port the way {@code iam.AssignedWorkload} was, because its return types ARE
 * the domain aggregates — a port declared in {@code platform} would still have to name {@code
 * Customer}. So it moved to the package that owns the data instead, and {@code platform} now has
 * no visibility concern at all.
 *
 * <p><b>Restriction is derived here, from the JWT role claim, and deliberately not shared.</b>
 * {@code sales.SalesVisibility} derives its own. Post-split these are separate deployables that
 * cannot share a decision method, so the duplication is the design and not an oversight — see spec
 * §3.2. VisibilityContract (test scope) is what keeps the two in agreement, and H6's SALES_MANAGER
 * tier must be implemented in both.
 */
@Component
public class CustomerVisibility {

    /**
     * Compared as a String, matching {@code platform.security.RoleGuard}'s idiom and for its
     * reason: {@code TenantPrincipal.role} is a String, {@code iam.Role} lives in {@code iam}, and
     * depending on {@code iam} for a type would put a compile edge on what becomes a separate
     * deployable. The literal is checked against the enum in test scope
     * (CustomerVisibilityRoleLiteralTest, added in Task 6), which may depend on anything.
     */
    static final String RESTRICTED_ROLE = "SALES_EXEC";

    private final CustomerRepository customers;

    public CustomerVisibility(CustomerRepository customers) {
        this.customers = customers;
    }

    public Optional<Customer> find(UUID id) {
        return customers.findOne(spec().and(hasId(id)));
    }

    public Page<Customer> page(Specification<Customer> filter, Pageable pageable) {
        return customers.findAll(and(spec(), filter), pageable);
    }

    /** Visibility without materialising the row, for the subject gate in {@code sales}. */
    public boolean isVisible(UUID id) {
        return find(id).isPresent();
    }

    /**
     * The ownership predicate for Customer rows, exposed so {@code sales} can apply it to the
     * Customer root inside its own subquery rather than restating the rule. That keeps ONE
     * definition of "which customers are mine".
     *
     * <p>A Customer carries its own {@code assigned_to}, and the IS NULL branch is reachable
     * because {@code customer.assigned_to} is nullable — unlike {@code follow_up.assigned_to}.
     */
    public Specification<Customer> spec() {
        if (unrestricted()) return unrestrictedSpec();
        UUID me = currentUserId();
        return (root, query, cb) -> cb.or(cb.equal(root.get("assignedTo"), me), cb.isNull(root.get("assignedTo")));
    }

    /**
     * Only SALES_EXEC is restricted; every other role — and an absent principal — is unrestricted.
     *
     * <p>A deliberate fail-OPEN default, safe only because this is not a security boundary. The
     * tenant wall is RLS, which still applies to every query built here. Two cases depend on it:
     * internal flows running with no principal or a synthetic one (async listeners,
     * TenantJobRunner's "SYSTEM", tenant provisioning), and any role added later, which must not
     * start silently hiding rows from users who could see them the day before. A new restricted
     * role is an explicit edit here AND in sales.SalesVisibility.
     */
    private boolean unrestricted() {
        return TenantContext.get().map(p -> !RESTRICTED_ROLE.equals(p.role())).orElse(true);
    }

    private static UUID currentUserId() {
        return TenantContext.get().map(TenantContext.TenantPrincipal::userId).orElse(null);
    }

    /** Empty conjunction — the same always-true idiom OrderSpecifications.filter uses. */
    private static <T> Specification<T> unrestrictedSpec() {
        return (root, query, cb) -> cb.and();
    }

    private static Specification<Customer> hasId(UUID id) {
        return (root, query, cb) -> cb.equal(root.get("id"), id);
    }

    /**
     * {@code Specification.and(null)} THROWS in the Spring Data JPA version this project is on —
     * it is not the null-safe no-op it looks like. A caller-supplied filter is routinely null (an
     * unfiltered list view), so guard here rather than at every call site.
     */
    private static <T> Specification<T> and(Specification<T> base, Specification<T> filter) {
        return filter == null ? base : base.and(filter);
    }
}
