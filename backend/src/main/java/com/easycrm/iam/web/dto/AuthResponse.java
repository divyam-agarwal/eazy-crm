package com.easycrm.iam.web.dto;

import java.util.UUID;

/**
 * Every session-issuing response (signup, login, invitation accept, refresh). Carries the same
 * identity as MeResponse so a browser needs no follow-up /me call and can detect a principal change
 * on refresh. The refresh token is NOT here: it travels only in the easycrm_rt httpOnly cookie
 * (spec 2026-09-14-f0 §3.1).
 */
public record AuthResponse(
        String accessToken, UUID userId, UUID tenantId, String tenantSlug, String email, String role) {}
