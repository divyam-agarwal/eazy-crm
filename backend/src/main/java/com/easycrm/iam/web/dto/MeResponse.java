package com.easycrm.iam.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(requiredProperties = {"userId", "tenantId", "tenantSlug", "email", "role"})
public record MeResponse(UUID userId, UUID tenantId, String email, String role, String tenantSlug) {}
