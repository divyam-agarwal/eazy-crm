package com.easycrm.crm;

import com.easycrm.crm.web.dto.ContactRequest;
import com.easycrm.crm.web.dto.ContactResponse;
import com.easycrm.platform.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ContactService {

    private final ContactRepository contacts;
    private final CustomerRepository customers;

    public ContactService(ContactRepository contacts, CustomerRepository customers) {
        this.contacts = contacts;
        this.customers = customers;
    }

    @Transactional
    public ContactResponse add(UUID customerId, ContactRequest req) {
        requireCustomer(customerId);
        Contact saved = contacts.save(new Contact(customerId, req.name(), req.phone(),
            req.whatsappNumber(), req.email(), req.designation(), Boolean.TRUE.equals(req.isPrimary())));
        return ContactResponse.of(saved);
    }

    @Transactional(readOnly = true)
    public List<ContactResponse> list(UUID customerId) {
        requireCustomer(customerId);
        return contacts.findByCustomerId(customerId).stream().map(ContactResponse::of).toList();
    }

    @Transactional
    public ContactResponse update(UUID customerId, UUID contactId, ContactRequest req) {
        Contact c = find(customerId, contactId);
        c.update(req.name(), req.phone(), req.whatsappNumber(), req.email(),
                 req.designation(), Boolean.TRUE.equals(req.isPrimary()));
        return ContactResponse.of(c);
    }

    @Transactional
    public void delete(UUID customerId, UUID contactId) {
        contacts.delete(find(customerId, contactId));
    }

    private void requireCustomer(UUID customerId) {
        customers.findById(customerId)
            .orElseThrow(() -> new NotFoundException("customer not found"));
    }

    private Contact find(UUID customerId, UUID contactId) {
        Contact c = contacts.findById(contactId)
            .orElseThrow(() -> new NotFoundException("contact not found"));
        if (!c.getCustomerId().equals(customerId)) {
            throw new NotFoundException("contact not found");
        }
        return c;
    }
}
