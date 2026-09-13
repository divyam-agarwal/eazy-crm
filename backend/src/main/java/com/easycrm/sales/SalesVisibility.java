package com.easycrm.sales;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerVisibility;
import com.easycrm.platform.tenancy.TenantContext;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/**
 * The ONLY class permitted to read EnquiryRepository, QuotationRepository, OrderRepository and
 * FollowUpRepository. VisibilityScopingArchTest fails the build on any other reader outside its
 * allowlist. Services keep their repositories for save() and the allowlisted pre-checks.
 *
 * <p>Moved here from {@code platform.visibility} in Wave 1.6 — see spec
 * 2026-09-13-wave-1.6-module-boundaries-design.md §3.1 for why a port in {@code platform} was not
 * an option, and crm.CustomerVisibility for the twin on the other aggregate.
 *
 * <p><b>Two different visibility shapes live here, and the asymmetry is the point</b> (spec
 * 2026-08-29-record-visibility-design.md §4.1). An Enquiry and a FollowUp carry their own
 * {@code assigned_to}, so their visibility is intrinsic. A Quotation and an Order do not — they
 * derive it from their customer, which is why this class asks {@code crm.CustomerVisibility} for
 * the Customer predicate instead of restating it.
 *
 * <p><b>NOT YET SPLIT-READY, deliberately recorded.</b> {@code viaCustomer} is a SQL subquery from
 * a sales root onto {@code crm}'s customer table. Relocating it here fixed the package cycle and
 * made it LOOK local; post-split, sales-svc cannot join to master-data-svc's tables. The
 * split-ready answer is for quotation/sales_order to carry their own owner, synced from the
 * customer — a data-model change belonging to roadmap item 3b (cross-service data access), not to
 * this slice. See spec §5.2.
 */
@Component
public class SalesVisibility {

    /** See crm.CustomerVisibility.RESTRICTED_ROLE for why this is a String and duplicated. */
    static final String RESTRICTED_ROLE = "SALES_EXEC";

    private final CustomerVisibility customerVisibility;
    private final EnquiryRepository enquiries;
    private final QuotationRepository quotations;
    private final OrderRepository orders;
    private final FollowUpRepository followUps;

    public SalesVisibility(
            CustomerVisibility customerVisibility,
            EnquiryRepository enquiries,
            QuotationRepository quotations,
            OrderRepository orders,
            FollowUpRepository followUps) {
        this.customerVisibility = customerVisibility;
        this.enquiries = enquiries;
        this.quotations = quotations;
        this.orders = orders;
        this.followUps = followUps;
    }

    public Optional<Enquiry> findEnquiry(UUID id) {
        return enquiries.findOne(enquirySpec().and(hasId(id)));
    }

    public Optional<Quotation> findQuotation(UUID id) {
        return quotations.findOne(this.<Quotation>viaCustomer("customerId").and(hasId(id)));
    }

    public Optional<Order> findOrder(UUID id) {
        return orders.findOne(this.<Order>viaCustomer("customerId").and(hasId(id)));
    }

    public Optional<FollowUp> findFollowUp(UUID id) {
        return followUps.findOne(followUpSpec().and(hasId(id)));
    }

    public Page<Enquiry> pageEnquiries(Specification<Enquiry> filter, Pageable pageable) {
        return enquiries.findAll(and(enquirySpec(), filter), pageable);
    }

    public Page<Quotation> pageQuotations(Specification<Quotation> filter, Pageable pageable) {
        return quotations.findAll(and(this.<Quotation>viaCustomer("customerId"), filter), pageable);
    }

    public Page<Order> pageOrders(Specification<Order> filter, Pageable pageable) {
        return orders.findAll(and(this.<Order>viaCustomer("customerId"), filter), pageable);
    }

    public Page<FollowUp> pageFollowUps(Specification<FollowUp> filter, Pageable pageable) {
        return followUps.findAll(and(followUpSpec(), filter), pageable);
    }

    /**
     * Unpaged list read, for internal sweeps that must see every matching row rather than a page
     * of them. Exists here and not on the caller because QuotationRepository is guarded. Under the
     * synthetic SYSTEM principal the policy is unrestricted, so the filter argument does the real
     * work; the routing is what the guard requires.
     */
    public List<Quotation> listQuotations(Specification<Quotation> filter) {
        return quotations.findAll(and(this.<Quotation>viaCustomer("customerId"), filter));
    }

    /** An Enquiry carries its own assigned_to, and enquiry.assigned_to is nullable. */
    private Specification<Enquiry> enquirySpec() {
        if (unrestricted()) return unrestrictedSpec();
        UUID me = currentUserId();
        return (root, query, cb) -> cb.or(cb.equal(root.get("assignedTo"), me), cb.isNull(root.get("assignedTo")));
    }

    /**
     * A follow-up carries its own owner, so its visibility is intrinsic rather than derived from a
     * subject — the other half of the asymmetry in spec §4.1.
     *
     * <p>Note this is NOT the enquiry shape: {@code follow_up.assigned_to} is NOT NULL, because a
     * follow-up nobody owns is precisely the failure the feature exists to prevent, so the IS NULL
     * branch the other aggregates carry would be unreachable code here.
     */
    private Specification<FollowUp> followUpSpec() {
        if (unrestricted()) return unrestrictedSpec();
        UUID me = currentUserId();
        return (root, query, cb) -> cb.equal(root.get("assignedTo"), me);
    }

    /**
     * The row has no assigned_to of its own and derives visibility from its customer. The
     * subquery's Customer root is itself {@code @TenantId}-scoped and runs under RLS, so it cannot
     * reach another tenant's customers.
     *
     * <p>The Customer predicate comes from {@code crm.CustomerVisibility.spec()} applied to this
     * subquery's root, rather than being restated here — one definition of "which customers are
     * mine", owned by the package that owns the table. A null predicate (an unrestricted spec that
     * produced nothing) is treated as always-true.
     *
     * <p><b>The unrestricted early return is load-bearing, not an optimisation.</b> This schema
     * declares no foreign keys at all and {@code quotation.customer_id} is a bare {@code UUID NOT
     * NULL}, so a row whose customer no longer exists is representable. Always building the EXISTS
     * would additionally require the customer row to EXIST, which would flip such an orphan from
     * visible to invisible for an unrestricted role — a behaviour change, not a refactor. So the
     * subquery is built only on the path that actually needs to narrow anything.
     */
    private <T> Specification<T> viaCustomer(String customerIdAttribute) {
        if (unrestricted()) return unrestrictedSpec();
        return (root, query, cb) -> {
            Subquery<UUID> sub = query.subquery(UUID.class);
            Root<Customer> c = sub.from(Customer.class);
            sub.select(c.get("id"));
            Predicate visible = customerVisibility.spec().toPredicate(c, query, cb);
            Predicate sameCustomer = cb.equal(c.get("id"), root.get(customerIdAttribute));
            sub.where(visible == null ? sameCustomer : cb.and(sameCustomer, visible));
            return cb.exists(sub);
        };
    }

    /** See crm.CustomerVisibility.unrestricted() for the fail-open reasoning. */
    private boolean unrestricted() {
        return TenantContext.get().map(p -> !RESTRICTED_ROLE.equals(p.role())).orElse(true);
    }

    private static UUID currentUserId() {
        return TenantContext.get().map(TenantContext.TenantPrincipal::userId).orElse(null);
    }

    private static <T> Specification<T> unrestrictedSpec() {
        return (root, query, cb) -> cb.and();
    }

    private static <T> Specification<T> hasId(UUID id) {
        return (root, query, cb) -> cb.equal(root.get("id"), id);
    }

    /** {@code Specification.and(null)} THROWS in this Spring Data version. Guard centrally. */
    private static <T> Specification<T> and(Specification<T> base, Specification<T> filter) {
        return filter == null ? base : base.and(filter);
    }
}
