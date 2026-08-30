package com.easycrm.crm;

import com.easycrm.crm.web.dto.CustomerRequest;
import com.easycrm.crm.web.dto.CustomerResponse;
import com.easycrm.iam.UserRepository;
import com.easycrm.iam.UserStatus;
import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.error.ValidationException;
import com.easycrm.platform.gst.Gstin;
import com.easycrm.platform.gst.StateCode;
import com.easycrm.platform.visibility.VisibleFinder;
import com.easycrm.platform.web.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class CustomerService {

    private final CustomerRepository customers;
    private final VisibleFinder finder;
    private final UserRepository users;

    public CustomerService(CustomerRepository customers, VisibleFinder finder, UserRepository users) {
        this.customers = customers;
        this.finder = finder;
        this.users = users;
    }

    @Transactional
    public CustomerResponse create(CustomerRequest req) {
        Resolved r = resolveGstinAndState(req);
        requireAssignableUser(req.assignedTo());
        if (r.gstin() != null) {
            customers.findByGstin(r.gstin()).ifPresent(c -> {
                throw new ConflictException("customer with this GSTIN already exists");
            });
        }
        Customer saved = customers.save(new Customer(req.businessName(), r.gstin(), r.stateCode(),
            req.billingAddress(), req.shippingAddress(), creditDays(req),
            req.assignedTo(), req.priceListId(), req.source()));
        return CustomerResponse.of(saved);
    }

    @Transactional(readOnly = true)
    public CustomerResponse get(UUID id) { return CustomerResponse.of(find(id)); }

    @Transactional(readOnly = true)
    public PageResponse<CustomerResponse> list(Boolean active, Pageable pageable) {
        return PageResponse.of(
            finder.pageCustomers(CustomerSpecifications.filter(active), pageable)
                .map(CustomerResponse::of));
    }

    @Transactional
    public CustomerResponse update(UUID id, CustomerRequest req) {
        Resolved r = resolveGstinAndState(req);
        requireAssignableUser(req.assignedTo());
        Customer c = find(id);
        c.update(req.businessName(), r.gstin(), r.stateCode(), req.billingAddress(),
            req.shippingAddress(), creditDays(req), req.assignedTo(), req.priceListId(), req.source());
        return CustomerResponse.of(c);
    }

    @Transactional
    public CustomerResponse deactivate(UUID id) {
        Customer c = find(id); c.deactivate(); return CustomerResponse.of(c);
    }

    @Transactional
    public CustomerResponse activate(UUID id) {
        Customer c = find(id); c.activate(); return CustomerResponse.of(c);
    }

    /**
     * Cross-tenant rows are invisible to RLS and out-of-scope rows are invisible to the
     * visibility policy. "Not there", "not this tenant's" and "not yours" all 404 — the
     * caller must not be able to tell them apart.
     */
    private Customer find(UUID id) {
        return finder.findCustomer(id)
            .orElseThrow(() -> new NotFoundException("customer not found"));
    }

    private int creditDays(CustomerRequest req) {
        return req.creditDays() == null ? 0 : req.creditDays();
    }

    /**
     * A non-null assignedTo must name an ACTIVE user in this tenant. User is tenant-scoped,
     * so RLS already makes a cross-tenant id come back empty -- no tenant check is needed
     * here and adding one would be hand-written tenant filtering.
     *
     * <p>Without this, a typo'd UUID makes a record visible to nobody below manager,
     * silently and permanently, because unassigned-means-visible only applies to NULL.
     */
    private void requireAssignableUser(UUID assignedTo) {
        if (assignedTo == null) return;
        users.findById(assignedTo)
            .filter(u -> u.getStatus() == UserStatus.ACTIVE)
            .orElseThrow(() -> new ValidationException("assignedTo", "must be an active user"));
    }

    /** GSTIN present ⇒ validate checksum, derive state (must match if supplied). Absent ⇒ require valid state_code. */
    private Resolved resolveGstinAndState(CustomerRequest req) {
        if (req.gstin() != null && !req.gstin().isBlank()) {
            Gstin g = Gstin.parse(req.gstin()); // validates charset, checksum, and state prefix
            String derived = g.stateCode();
            if (req.stateCode() != null && !req.stateCode().isBlank()
                    && !req.stateCode().equals(derived)) {
                throw new ValidationException("stateCode", "must match the GSTIN state code");
            }
            return new Resolved(g.value(), derived);
        }
        if (req.stateCode() == null || req.stateCode().isBlank()) {
            throw new ValidationException("stateCode", "state code is required when GSTIN is absent");
        }
        StateCode.requireValid(req.stateCode());
        return new Resolved(null, req.stateCode());
    }

    private record Resolved(String gstin, String stateCode) {}
}
