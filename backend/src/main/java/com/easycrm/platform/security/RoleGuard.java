package com.easycrm.platform.security;

import com.easycrm.platform.error.ForbiddenException;
import com.easycrm.platform.tenancy.TenantContext;
import org.springframework.stereotype.Component;

/**
 * The one place an "is the caller an owner?" check lives. Extracted from
 * {@code TenantService}'s private copy when {@code InvitationService} became the second
 * caller — one copy earlier than {@code AssignableUsers} was extracted, deliberately.
 *
 * <p>Lives in {@code platform.security} rather than {@code iam} to avoid a package cycle:
 * {@code iam} already depends on {@code tenant}, so a guard in {@code iam} called from
 * {@code TenantService} would make the two mutually dependent. It therefore compares the
 * literal {@code "OWNER"} rather than {@code Role.OWNER.name()} — {@code Role} lives in
 * {@code iam}, and {@code TenantPrincipal.role} is a String regardless.
 *
 * <p>The message is caller-supplied so each 403 body stays as specific as the hand-rolled
 * checks were.
 */
@Component
public class RoleGuard {

    private static final String OWNER = "OWNER";

    public void requireOwner(String message) {
        String role =
                TenantContext.get().map(TenantContext.TenantPrincipal::role).orElse(null);
        if (!OWNER.equals(role)) {
            throw new ForbiddenException(message);
        }
    }
}
