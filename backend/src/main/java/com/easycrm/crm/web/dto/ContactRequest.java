package com.easycrm.crm.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ContactRequest(
    @NotBlank String name,
    String phone,
    String whatsappNumber,
    String email,
    String designation,
    Boolean isPrimary) {}
