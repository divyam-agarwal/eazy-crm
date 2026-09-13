package com.easycrm.platform.visibility;

import com.easycrm.crm.CustomerVisibility;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.sales.SalesVisibility;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The polymorphic subject gate. Everything that filtered a specific aggregate has moved to the
 * package that owns that aggregate's table — {@code crm.CustomerVisibility} and {@code
 * sales.SalesVisibility} — leaving only the switch that resolves a {@link SubjectType}.
 *
 * <p>This class is the last thing keeping {@code platform} pointing at a domain package, and
 * ModuleDirectionArchTest stays red until it moves too (Wave 1.6 Task 5). It is a {@code sales}
 * concern, not a platform one: {@code SubjectType}'s three non-customer values are all sales
 * aggregates and the activity table it protects lives in {@code sales}.
 */
@Component
public class VisibleFinder {

    private final CustomerVisibility customerVisibility;
    private final SalesVisibility sales;

    public VisibleFinder(CustomerVisibility customerVisibility, SalesVisibility sales) {
        this.customerVisibility = customerVisibility;
        this.sales = sales;
    }

    /**
     * Resolves a polymorphic subject through the same visibility filter as a direct read,
     * returning the id unchanged so call sites can inline it. Cross-tenant, non-existent
     * and not-visible-to-you all surface as NotFoundException — the house 404 rule.
     *
     * <p>This is the ONLY thing protecting the activity table: ActivityRepository declares
     * no read that is not subject-scoped, so an activity cannot be reached without first
     * naming a subject, and a subject cannot be named without passing through here.
     * See spec 2026-08-30-activity-follow-up-design.md §4.2.
     */
    public UUID requireVisibleSubject(SubjectType type, UUID id) {
        boolean visible =
                switch (type) {
                    case CUSTOMER -> customerVisibility.isVisible(id);
                    case ENQUIRY -> sales.findEnquiry(id).isPresent();
                    case QUOTATION -> sales.findQuotation(id).isPresent();
                    case ORDER -> sales.findOrder(id).isPresent();
                };
        if (!visible) {
            throw new NotFoundException(type.name().toLowerCase() + " " + id + " was not found");
        }
        return id;
    }
}
