package com.easycrm.iam.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code acceptUrl} carries the plaintext token and is populated ONLY by the mint
 * response — the token is hashed at rest and cannot be recovered afterwards, so the
 * pending-list variant leaves it null.
 */
public record InvitationResponse(UUID id, String email, String role, Instant expiresAt, String acceptUrl) {}
