package com.easycrm.iam.web.dto;

import java.util.UUID;

public record AuthResponse(String accessToken, String refreshToken,
                           UUID tenantId, UUID userId, String role) {}
