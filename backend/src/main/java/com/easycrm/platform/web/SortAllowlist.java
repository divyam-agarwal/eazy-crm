package com.easycrm.platform.web;

import com.easycrm.platform.error.ValidationException;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
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

    /**
     * An unsorted {@code Pageable} (the client sent no {@code sort} parameter) emits no {@code
     * ORDER BY} at all, so Postgres is free to return rows in a different order on every page —
     * a row can be returned twice, or skipped, as a user pages through a list (spec §1.2). This
     * fills in the endpoint's default sort in that case only; a client-supplied sort — already
     * checked by {@link #require} — is left exactly as given.
     */
    public static Pageable withDefault(Pageable pageable, Sort defaultSort) {
        return pageable.getSort().isSorted()
                ? pageable
                : PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), defaultSort);
    }
}
