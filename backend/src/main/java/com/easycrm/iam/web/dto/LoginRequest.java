package com.easycrm.iam.web.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank String slug, @NotBlank String email, @NotBlank String password) {}
