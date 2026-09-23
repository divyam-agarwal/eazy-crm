package com.easycrm.crm;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

public final class CustomerSpecifications {

    private CustomerSpecifications() {}

    /** AND-composes whichever filters are non-null. Tenant scoping comes from RLS, not here. */
    public static Specification<Customer> filter(Boolean active, String q) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (active != null) ps.add(cb.equal(root.get("active"), active));
            String needle = q == null ? null : q.trim();
            if (needle != null && !needle.isEmpty()) {
                // lower(col) LIKE lower(%needle%) matches V36's lower(col) gin_trgm_ops indexes.
                // An index on the raw column would not be used by this expression.
                String pattern = "%" + needle.toLowerCase(Locale.ROOT) + "%";
                // Must stay ONE predicate: cb.and(ps.toArray(...)) ANDs every element in ps, so
                // adding these two cb.like(...) calls separately would turn this OR into an AND.
                // gstin is usually null, and LOWER(NULL) LIKE ... is never true, so a plain name
                // search would then match nothing -- it fails closed, not open.
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("businessName")), pattern),
                        cb.like(cb.lower(root.get("gstin")), pattern)));
            }
            return cb.and(ps.toArray(new Predicate[0])); // empty -> always-true conjunction
        };
    }
}
