package com.easycrm.crm.web;

import com.easycrm.crm.ContactService;
import com.easycrm.crm.web.dto.ContactRequest;
import com.easycrm.crm.web.dto.ContactResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers/{customerId}/contacts")
public class ContactController {

    private final ContactService service;

    public ContactController(ContactService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ContactResponse> add(@PathVariable UUID customerId,
                                               @Valid @RequestBody ContactRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.add(customerId, req));
    }

    @GetMapping
    public List<ContactResponse> list(@PathVariable UUID customerId) {
        return service.list(customerId);
    }

    @PutMapping("/{contactId}")
    public ContactResponse update(@PathVariable UUID customerId, @PathVariable UUID contactId,
                                  @Valid @RequestBody ContactRequest req) {
        return service.update(customerId, contactId, req);
    }

    @DeleteMapping("/{contactId}")
    public ResponseEntity<Void> delete(@PathVariable UUID customerId, @PathVariable UUID contactId) {
        service.delete(customerId, contactId);
        return ResponseEntity.noContent().build();
    }
}
