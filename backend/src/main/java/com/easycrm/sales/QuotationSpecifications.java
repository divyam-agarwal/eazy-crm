package com.easycrm.sales;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class QuotationSpecifications {

    private QuotationSpecifications() {}

    /** AND-composes whichever filters are non-null. Tenant scoping comes from RLS, not here. */
    public static Specification<Quotation> filter(QuotationStatus status, UUID customerId) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (status != null)     ps.add(cb.equal(root.get("status"), status));
            if (customerId != null) ps.add(cb.equal(root.get("customerId"), customerId));
            return cb.and(ps.toArray(new Predicate[0])); // empty -> always-true conjunction
        };
    }

    /**
     * The scheduled auto-expiry candidate set: SENT quotations whose CURRENT version's
     * validUntil is strictly before {@code asOf}. Strictly-before is the contract — a quote
     * valid until the 31st is valid for all of the 31st and expires once IST reaches the 1st.
     *
     * <p>A correlated EXISTS subquery rather than a join because Quotation holds a raw
     * currentVersionId UUID, not a @ManyToOne — the same idiom VisibilityPolicy.viaCustomer
     * uses. QuotationVersion is itself @TenantId-scoped and runs under RLS, so the subquery
     * cannot reach another tenant's versions.
     *
     * <p>The isNotNull is redundant against SQL's null semantics (NULL &lt; x is never true)
     * and is present to state the intent: an open-ended quotation never expires.
     */
    public static Specification<Quotation> expirableAsOf(LocalDate asOf) {
        return (root, query, cb) -> {
            Subquery<UUID> sub = query.subquery(UUID.class);
            Root<QuotationVersion> v = sub.from(QuotationVersion.class);
            sub.select(v.get("id"));
            sub.where(cb.and(
                cb.equal(v.get("id"), root.get("currentVersionId")),
                cb.isNotNull(v.get("validUntil")),
                cb.lessThan(v.<LocalDate>get("validUntil"), asOf)));
            return cb.and(
                cb.equal(root.get("status"), QuotationStatus.SENT),
                cb.exists(sub));
        };
    }
}
