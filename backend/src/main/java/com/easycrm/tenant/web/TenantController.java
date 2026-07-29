package com.easycrm.tenant.web;

import com.easycrm.tenant.TenantService;
import com.easycrm.tenant.web.dto.TenantProfileRequest;
import com.easycrm.tenant.web.dto.TenantResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenant")
public class TenantController {

    private final TenantService service;

    public TenantController(TenantService service) { this.service = service; }

    @GetMapping
    public TenantResponse get() { return service.get(); }

    @PatchMapping
    public TenantResponse patch(@Valid @RequestBody TenantProfileRequest req) {
        return service.updateProfile(req);
    }
}
