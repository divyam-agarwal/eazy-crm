package com.easycrm.crm;

import com.easycrm.crm.web.dto.ContactRequest;
import com.easycrm.crm.web.dto.ContactResponse;
import com.easycrm.platform.error.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContactService {

    private final ContactRepository contacts;
    private final CustomerVisibility customerVisibility;

    public ContactService(ContactRepository contacts, CustomerVisibility customerVisibility) {
        this.contacts = contacts;
        this.customerVisibility = customerVisibility;
    }

    @Transactional
    public ContactResponse add(UUID customerId, ContactRequest req) {
        requireCustomer(customerId);
        if (Boolean.TRUE.equals(req.isPrimary())) {
            demoteOtherPrimaries(customerId, null);
        }
        Contact saved = contacts.save(new Contact(
                customerId,
                req.name(),
                req.phone(),
                req.whatsappNumber(),
                req.email(),
                req.designation(),
                Boolean.TRUE.equals(req.isPrimary())));
        return ContactResponse.of(saved);
    }

    @Transactional(readOnly = true)
    public List<ContactResponse> list(UUID customerId) {
        requireCustomer(customerId);
        return contacts.findByCustomerId(customerId).stream()
                .map(ContactResponse::of)
                .toList();
    }

    @Transactional
    public ContactResponse update(UUID customerId, UUID contactId, ContactRequest req) {
        Contact c = find(customerId, contactId);
        if (Boolean.TRUE.equals(req.isPrimary())) {
            demoteOtherPrimaries(customerId, contactId);
        }
        c.update(
                req.name(),
                req.phone(),
                req.whatsappNumber(),
                req.email(),
                req.designation(),
                Boolean.TRUE.equals(req.isPrimary()));
        return ContactResponse.of(c);
    }

    @Transactional
    public void delete(UUID customerId, UUID contactId) {
        contacts.delete(find(customerId, contactId));
    }

    private void requireCustomer(UUID customerId) {
        customerVisibility.find(customerId).orElseThrow(() -> new NotFoundException("customer not found"));
    }

    private Contact find(UUID customerId, UUID contactId) {
        requireCustomer(customerId); // gate on the parent FIRST
        Contact c = contacts.findById(contactId).orElseThrow(() -> new NotFoundException("contact not found"));
        if (!c.getCustomerId().equals(customerId)) {
            throw new NotFoundException("contact not found");
        }
        return c;
    }

    /**
     * At most one primary contact per customer. Scoped to this customer only: a tenant-wide demotion
     * would quietly unset another customer's primary contact.
     *
     * <p>Runs inside the caller's @Transactional, so the demotion and the promotion commit together
     * or not at all — a half-applied promotion would leave zero primaries.
     */
    private void demoteOtherPrimaries(UUID customerId, UUID exceptContactId) {
        for (Contact existing : contacts.findByCustomerIdAndPrimaryTrue(customerId)) {
            if (!existing.getId().equals(exceptContactId)) {
                existing.demote();
            }
        }
    }
}
