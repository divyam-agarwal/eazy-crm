package com.easycrm.iam.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What the accept page needs to render: which workspace, which address, which role.
 * Reveals those to a token holder, which is exactly what the invitation message itself
 * would have said. No id and no token.
 */
@Schema(requiredProperties = {"businessName", "email", "role"})
public record InvitationPreviewResponse(String businessName, String email, String role) {}
