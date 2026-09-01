package com.easycrm.sales;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

public final class EnquirySpecifications {

    private EnquirySpecifications() {}

    /** AND-composes whichever filters are non-null. Tenant scoping comes from RLS, not here. */
    public static Specification<Enquiry> filter(EnquiryStage stage, UUID assignedTo, EnquirySource source) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (stage != null) ps.add(cb.equal(root.get("stage"), stage));
            if (assignedTo != null) ps.add(cb.equal(root.get("assignedTo"), assignedTo));
            if (source != null) ps.add(cb.equal(root.get("source"), source));
            return cb.and(ps.toArray(new Predicate[0])); // empty -> always-true conjunction
        };
    }
}
