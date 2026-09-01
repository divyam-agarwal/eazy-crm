package com.easycrm.iam.web.dto;

import java.time.Instant;
import java.util.UUID;

/** A workspace member as an owner sees them. Never carries passwordHash. */
public record MemberResponse(UUID id, String email, String phone, String role, String status, Instant createdAt) {}
