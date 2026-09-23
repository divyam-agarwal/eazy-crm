package com.easycrm.platform.web;

import com.easycrm.platform.error.ValidationException;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Rejects a `sort` field no endpoint declared.
 *
 * <p>Why this exists: `sort` binds straight into a Spring `Sort` with no validation, so an unknown
 * property used to reach JPA and throw there — a 500 on user-controllable input on every paged
 * endpoint. Binding succeeds, which is why none of the 400 bean-validation machinery is on this path;
 * see the spec's F1-3 note on the resulting 422.
 */
public final class SortAllowlist {

    private SortAllowlist() {}

    public static void require(Pageable pageable, Set<String> allowed) {
        for (Sort.Order order : pageable.getSort()) {
            if (!allowed.contains(order.getProperty())) {
                throw new ValidationException("sort", "unsupported sort field: " + order.getProperty(), "SORT_INVALID");
            }
        }
    }
}
