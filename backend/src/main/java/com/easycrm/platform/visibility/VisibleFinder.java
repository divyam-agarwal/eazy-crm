package com.easycrm.platform.visibility;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.sales.Enquiry;
import com.easycrm.sales.EnquiryRepository;
import com.easycrm.sales.Order;
import com.easycrm.sales.OrderRepository;
import com.easycrm.sales.Quotation;
import com.easycrm.sales.QuotationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * The ONLY class permitted to call a read method on the four visibility-scoped
 * repositories. VisibilityScopingArchTest fails the build on any other caller — see spec
 * 2026-08-29-record-visibility-design.md §8. Services keep their repositories for save()
 * and nothing else.
 */
@Component
public class VisibleFinder {

    private final VisibilityPolicy policy;
    private final CustomerRepository customers;
    private final EnquiryRepository enquiries;
    private final QuotationRepository quotations;
    private final OrderRepository orders;

    public VisibleFinder(VisibilityPolicy policy, CustomerRepository customers,
                         EnquiryRepository enquiries, QuotationRepository quotations,
                         OrderRepository orders) {
        this.policy = policy;
        this.customers = customers;
        this.enquiries = enquiries;
        this.quotations = quotations;
        this.orders = orders;
    }

    public Optional<Customer> findCustomer(UUID id) {
        return customers.findOne(policy.customers().and(hasId(id)));
    }

    public Optional<Enquiry> findEnquiry(UUID id) {
        return enquiries.findOne(policy.enquiries().and(hasId(id)));
    }

    public Optional<Quotation> findQuotation(UUID id) {
        return quotations.findOne(policy.quotations().and(hasId(id)));
    }

    public Optional<Order> findOrder(UUID id) {
        return orders.findOne(policy.orders().and(hasId(id)));
    }

    public Page<Customer> pageCustomers(Specification<Customer> filter, Pageable pageable) {
        return customers.findAll(and(policy.customers(), filter), pageable);
    }

    public Page<Enquiry> pageEnquiries(Specification<Enquiry> filter, Pageable pageable) {
        return enquiries.findAll(and(policy.enquiries(), filter), pageable);
    }

    public Page<Quotation> pageQuotations(Specification<Quotation> filter, Pageable pageable) {
        return quotations.findAll(and(policy.quotations(), filter), pageable);
    }

    public Page<Order> pageOrders(Specification<Order> filter, Pageable pageable) {
        return orders.findAll(and(policy.orders(), filter), pageable);
    }

    private static <T> Specification<T> hasId(UUID id) {
        return (root, query, cb) -> cb.equal(root.get("id"), id);
    }

    /**
     * {@code Specification.and(null)} throws in the Spring Data JPA version this project
     * is on — it is not the null-safe no-op the brief assumed. A caller-supplied filter is
     * routinely null (an unfiltered list view), so guard here rather than push the null
     * check onto every call site.
     */
    private static <T> Specification<T> and(Specification<T> base, Specification<T> filter) {
        return filter == null ? base : base.and(filter);
    }
}
