package com.easycrm.iam.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * {@code role} is a validated String rather than a {@code Role} parameter, for the same
 * reason InviteRequest does it: an unknown value must be a 400 from bean validation, not a
 * Jackson deserialisation failure. The pattern is deliberately identical to InviteRequest's.
 */
public record ChangeRoleRequest(
        @NotBlank
        @Pattern(regexp = "OWNER|SALES_MANAGER|SALES_EXEC", message = "role must be OWNER, SALES_MANAGER or SALES_EXEC")
        String role) {}
