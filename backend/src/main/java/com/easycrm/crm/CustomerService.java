package com.easycrm.crm;

import com.easycrm.crm.web.dto.CustomerRequest;
import com.easycrm.crm.web.dto.CustomerResponse;
import com.easycrm.platform.error.ConflictException;
import com.easycrm.platform.error.NotFoundException;
import com.easycrm.platform.error.ValidationException;
import com.easycrm.platform.gst.Gstin;
import com.easycrm.platform.gst.StateCode;
import com.easycrm.platform.web.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class CustomerService {

    private final CustomerRepository customers;

    public CustomerService(CustomerRepository customers) { this.customers = customers; }

    @Transactional
    public CustomerResponse create(CustomerRequest req) {
        Resolved r = resolveGstinAndState(req);
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
        var page = (active == null)
            ? customers.findAll(pageable)
            : customers.findByActive(active, pageable);
        return PageResponse.of(page.map(CustomerResponse::of));
    }

    @Transactional
    public CustomerResponse update(UUID id, CustomerRequest req) {
        Resolved r = resolveGstinAndState(req);
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

    private Customer find(UUID id) {
        return customers.findById(id).orElseThrow(() -> new NotFoundException("customer not found"));
    }

    private int creditDays(CustomerRequest req) {
        return req.creditDays() == null ? 0 : req.creditDays();
    }

    /** GSTIN present ⇒ validate checksum, derive state (must match if supplied). Absent ⇒ require valid state_code. */
    private Resolved resolveGstinAndState(CustomerRequest req) {
        if (req.gstin() != null && !req.gstin().isBlank()) {
            Gstin g = Gstin.parse(req.gstin()); // throws 422 on bad checksum
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
