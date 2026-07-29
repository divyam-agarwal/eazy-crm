package com.easycrm.tenant.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record TenantProfileRequest(
    @Size(max = 512) String address,
    @Size(max = 20) String phone,
    @Size(max = 255) @Email String email) {}
