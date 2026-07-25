package com.easycrm.iam.web.dto;

import java.util.UUID;

public record MeResponse(UUID userId, UUID tenantId, String email, String role, String tenantSlug) {}
