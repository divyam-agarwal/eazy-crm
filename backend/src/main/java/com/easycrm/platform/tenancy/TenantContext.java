package com.easycrm.platform.tenancy;

import java.util.Optional;
import java.util.UUID;

public final class TenantContext {

    public record TenantPrincipal(UUID tenantId, UUID userId, String role) {}

    private static final ThreadLocal<TenantPrincipal> HOLDER = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(TenantPrincipal principal) { HOLDER.set(principal); }

    public static Optional<TenantPrincipal> get() { return Optional.ofNullable(HOLDER.get()); }

    public static UUID tenantId() {
        TenantPrincipal p = HOLDER.get();
        return p == null ? null : p.tenantId();
    }

    public static void clear() { HOLDER.remove(); }

    public static void runAs(TenantPrincipal principal, Runnable body) {
        TenantPrincipal previous = HOLDER.get();
        HOLDER.set(principal);
        try {
            body.run();
        } finally {
            if (previous == null) HOLDER.remove(); else HOLDER.set(previous);
        }
    }
}
