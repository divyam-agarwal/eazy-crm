package com.easycrm.crm.web;

import com.easycrm.crm.CustomerService;
import com.easycrm.crm.web.dto.CustomerRequest;
import com.easycrm.crm.web.dto.CustomerResponse;
import com.easycrm.platform.web.PageResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CustomerRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{id}")
    public CustomerResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping
    public PageResponse<CustomerResponse> list(
            @RequestParam(required = false) Boolean active, @ParameterObject Pageable pageable) {
        return service.list(active, pageable);
    }

    @PutMapping("/{id}")
    public CustomerResponse update(@PathVariable UUID id, @Valid @RequestBody CustomerRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/deactivate")
    public CustomerResponse deactivate(@PathVariable UUID id) {
        return service.deactivate(id);
    }

    @PostMapping("/{id}/activate")
    public CustomerResponse activate(@PathVariable UUID id) {
        return service.activate(id);
    }
}
