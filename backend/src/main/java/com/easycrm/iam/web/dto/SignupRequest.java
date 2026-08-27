package com.easycrm.iam.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
    @NotBlank @Pattern(regexp = "[a-z0-9-]{3,64}", message = "slug must be 3-64 chars a-z 0-9 -")
    String slug,
    @NotBlank String businessName,
    // @Pattern is shape only. AuthService.signup runs StateCode.requireValid, and Gstin.parse when
    // a GSTIN is supplied — "two digits" is not the same as "a GST state code" (MF1).
    @NotBlank @Pattern(regexp = "\\d{2}", message = "stateCode must be 2 digits") String stateCode,
    String gstin,
    @NotBlank @Email String email,
    String phone,
    @NotBlank @Size(min = 8, message = "password must be at least 8 characters") String password
) {}
