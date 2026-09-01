package com.easycrm.iam.web.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * No token field, deliberately: the token is hashed at rest and cannot be recovered. A
 * "resend" is revoke + re-invite, which mints a new one.
 *
 * <p>{@code expired} is DERIVED at read time and never stored — invitation expiry is lazy
 * (spec §7), unlike quotation expiry, which is materialised by a nightly job.
 */
public record PendingInvitationResponse(UUID id, String email, String role,
                                        Instant expiresAt, boolean expired) {}
