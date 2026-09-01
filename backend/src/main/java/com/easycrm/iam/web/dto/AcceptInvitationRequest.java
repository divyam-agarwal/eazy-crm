package com.easycrm.iam.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The password constraints are copied from SignupRequest verbatim so an invited user's
 * password rules cannot drift from a self-serve owner's.
 */
public record AcceptInvitationRequest(
    @NotBlank @Size(min = 8, message = "password must be at least 8 characters")
    String password,
    String phone
) {}
