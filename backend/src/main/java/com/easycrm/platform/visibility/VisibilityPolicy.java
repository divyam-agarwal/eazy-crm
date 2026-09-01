package com.easycrm.platform.visibility;

import com.easycrm.crm.Customer;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.sales.Enquiry;
import com.easycrm.sales.FollowUp;
import com.easycrm.sales.Order;
import com.easycrm.sales.Quotation;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * Intra-tenant record visibility — a PRODUCT rule, deliberately not the tenant wall.
 * RLS and {@code @TenantId} enforce the tenant boundary; this decides which of a tenant's
 * own rows a principal may see. See spec 2026-08-29-record-visibility-design.md §1.
 */
@Component
public class VisibilityPolicy {

    /**
     * Only SALES_EXEC is restricted; every other role — and an absent principal — is
     * unrestricted.
     *
     * <p>This is a deliberate fail-OPEN default, and it is safe only because this class is
     * not a security boundary. The tenant wall is RLS, which still applies to every query
     * built here. Two cases depend on it: internal flows that run with no principal or a
     * synthetic one (async listeners, tenant provisioning), and any role added later, which
     * must not start silently hiding rows from users who could see them the day before.
     * A new restricted role is an explicit edit to this method.
     */
    public boolean unrestricted() {
        return TenantContext.get().map(p -> !"SALES_EXEC".equals(p.role())).orElse(true);
    }

    public Specification<Customer> customers() {
        return ownedOrUnassigned();
    }

    public Specification<Enquiry> enquiries() {
        return ownedOrUnassigned();
    }

    public Specification<Quotation> quotations() {
        return viaCustomer("customerId");
    }

    public Specification<Order> orders() {
        return viaCustomer("customerId");
    }

    /**
     * A follow-up carries its own owner, so its visibility is intrinsic rather than
     * derived from a subject — this is the half of the asymmetry described in spec §4.1.
     *
     * <p>Note this is NOT ownedOrUnassigned(): follow_up.assigned_to is NOT NULL, because
     * a follow-up nobody owns is precisely the failure this feature exists to prevent, so
     * the IS NULL branch the other aggregates carry would be unreachable code here.
     */
    public Specification<FollowUp> followUps() {
        if (unrestricted()) return unrestrictedSpec();
        UUID me = currentUserId();
        return (root, query, cb) -> cb.equal(root.get("assignedTo"), me);
    }

    /** The row carries its own assigned_to. */
    private <T> Specification<T> ownedOrUnassigned() {
        if (unrestricted()) return unrestrictedSpec();
        UUID me = currentUserId();
        return (root, query, cb) -> cb.or(cb.equal(root.get("assignedTo"), me), cb.isNull(root.get("assignedTo")));
    }

    /**
     * The row has no assigned_to of its own and derives visibility from its customer.
     * The subquery's Customer root is itself {@code @TenantId}-scoped and runs under RLS,
     * so it cannot reach another tenant's customers.
     */
    private <T> Specification<T> viaCustomer(String customerIdAttribute) {
        if (unrestricted()) return unrestrictedSpec();
        UUID me = currentUserId();
        return (root, query, cb) -> {
            Subquery<UUID> sub = query.subquery(UUID.class);
            Root<Customer> c = sub.from(Customer.class);
            sub.select(c.get("id"));
            sub.where(cb.and(
                    cb.equal(c.get("id"), root.get(customerIdAttribute)),
                    cb.or(cb.equal(c.get("assignedTo"), me), cb.isNull(c.get("assignedTo")))));
            return cb.exists(sub);
        };
    }

    /** Empty conjunction — the same always-true idiom OrderSpecifications.filter uses. */
    private static <T> Specification<T> unrestrictedSpec() {
        return (root, query, cb) -> cb.and();
    }

    private static UUID currentUserId() {
        return TenantContext.get().map(TenantContext.TenantPrincipal::userId).orElse(null);
    }
}
