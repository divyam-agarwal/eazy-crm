package com.easycrm.crm;

import com.easycrm.crm.web.dto.CustomerRequest;
import com.easycrm.crm.web.dto.CustomerResponse;
import com.easycrm.iam.AssignableUsers;
import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.error.ValidationException;
import com.easycrm.platform.gst.Gstin;
import com.easycrm.platform.gst.StateCode;
import com.easycrm.platform.web.PageResponse;
import com.easycrm.platform.web.SortAllowlist;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {

    /** Sort fields a client may name. Anything else is a 422, not a 500 from JPA (F1-3). */
    private static final Set<String> SORTABLE = Set.of("businessName", "createdAt", "updatedAt");

    /** Applied when the client sends no `sort` at all, so paging is stable (spec §1.2). */
    private static final Sort DEFAULT_SORT = Sort.by("businessName").ascending();

    private final CustomerRepository customers;
    private final CustomerVisibility customerVisibility;
    private final AssignableUsers assignableUsers;

    public CustomerService(
            CustomerRepository customers, CustomerVisibility customerVisibility, AssignableUsers assignableUsers) {
        this.customers = customers;
        this.customerVisibility = customerVisibility;
        this.assignableUsers = assignableUsers;
    }

    @Transactional
    public CustomerResponse create(CustomerRequest req) {
        Resolved r = resolveGstinAndState(req);
        assignableUsers.require(req.assignedTo());
        if (r.gstin() != null) {
            customers.findByGstin(r.gstin()).ifPresent(c -> {
                throw new ConflictException(
                        "customer with this GSTIN already exists",
                        Map.of("gstin", "customer with this GSTIN already exists"),
                        Map.of("gstin", "GSTIN_DUPLICATE"));
            });
        }
        Customer saved = customers.save(new Customer(
                req.businessName(),
                r.gstin(),
                r.stateCode(),
                req.billingAddress(),
                req.shippingAddress(),
                creditDays(req),
                req.assignedTo(),
                req.priceListId(),
                req.source()));
        return CustomerResponse.of(saved);
    }

    @Transactional(readOnly = true)
    public CustomerResponse get(UUID id) {
        return CustomerResponse.of(find(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerResponse> list(Boolean active, String q, Pageable pageable) {
        SortAllowlist.require(pageable, SORTABLE);
        Pageable effective = SortAllowlist.withDefault(pageable, DEFAULT_SORT);
        return PageResponse.of(customerVisibility
                .page(CustomerSpecifications.filter(active, q), effective)
                .map(CustomerResponse::of));
    }

    @Transactional
    public CustomerResponse update(UUID id, CustomerRequest req) {
        Resolved r = resolveGstinAndState(req);
        assignableUsers.require(req.assignedTo());
        if (r.gstin() != null) {
            customers.findByGstin(r.gstin()).ifPresent(c -> {
                if (!c.getId().equals(id)) {
                    throw new ConflictException(
                            "customer with this GSTIN already exists",
                            Map.of("gstin", "customer with this GSTIN already exists"),
                            Map.of("gstin", "GSTIN_DUPLICATE"));
                }
            });
        }
        Customer c = find(id);
        c.update(
                req.businessName(),
                r.gstin(),
                r.stateCode(),
                req.billingAddress(),
                req.shippingAddress(),
                creditDays(req),
                req.assignedTo(),
                req.priceListId(),
                req.source());
        return CustomerResponse.of(c);
    }

    @Transactional
    public CustomerResponse deactivate(UUID id) {
        Customer c = find(id);
        c.deactivate();
        return CustomerResponse.of(c);
    }

    @Transactional
    public CustomerResponse activate(UUID id) {
        Customer c = find(id);
        c.activate();
        return CustomerResponse.of(c);
    }

    /**
     * Cross-tenant rows are invisible to RLS and out-of-scope rows are invisible to the
     * visibility policy. "Not there", "not this tenant's" and "not yours" all 404 — the
     * caller must not be able to tell them apart.
     */
    private Customer find(UUID id) {
        return customerVisibility.find(id).orElseThrow(() -> new NotFoundException("customer not found"));
    }

    private int creditDays(CustomerRequest req) {
        return req.creditDays() == null ? 0 : req.creditDays();
    }

    /** GSTIN present ⇒ validate checksum, derive state (must match if supplied). Absent ⇒ require valid state_code. */
    private Resolved resolveGstinAndState(CustomerRequest req) {
        if (req.gstin() != null && !req.gstin().isBlank()) {
            Gstin g = Gstin.parse(req.gstin()); // validates charset, checksum, and state prefix
            String derived = g.stateCode();
            if (req.stateCode() != null
                    && !req.stateCode().isBlank()
                    && !req.stateCode().equals(derived)) {
                throw new ValidationException(
                        "stateCode", "must match the GSTIN state code", "STATE_CODE_GSTIN_MISMATCH");
            }
            return new Resolved(g.value(), derived);
        }
        if (req.stateCode() == null || req.stateCode().isBlank()) {
            throw new ValidationException(
                    "stateCode", "state code is required when GSTIN is absent", "STATE_CODE_REQUIRED");
        }
        StateCode.requireValid(req.stateCode());
        return new Resolved(null, req.stateCode());
    }

    private record Resolved(String gstin, String stateCode) {}
}
