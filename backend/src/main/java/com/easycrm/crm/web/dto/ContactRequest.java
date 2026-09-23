package com.easycrm.crm.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Sizes mirror V1x__contact.sql's column widths exactly; a longer value would fail at the DB. */
public record ContactRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 20) String phone,
        @Size(max = 20) String whatsappNumber,
        @Email @Size(max = 255) String email,
        @Size(max = 128) String designation,
        Boolean isPrimary) {}
