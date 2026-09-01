package com.easycrm.sales;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

public final class OrderSpecifications {

    private OrderSpecifications() {}

    /** AND-composes whichever filters are non-null. Tenant scoping comes from RLS, not here. */
    public static Specification<Order> filter(OrderStatus status, UUID customerId) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (status != null) ps.add(cb.equal(root.get("status"), status));
            if (customerId != null) ps.add(cb.equal(root.get("customerId"), customerId));
            return cb.and(ps.toArray(new Predicate[0])); // empty -> always-true conjunction
        };
    }
}
