package com.easycrm.platform.tenancy;

import java.util.Optional;
import java.util.UUID;

public final class TenantContext {

    public record TenantPrincipal(UUID tenantId, UUID userId, String role) {}

    private static final ThreadLocal<TenantPrincipal> HOLDER = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(TenantPrincipal principal) {
        HOLDER.set(principal);
    }

    public static Optional<TenantPrincipal> get() {
        return Optional.ofNullable(HOLDER.get());
    }

    public static UUID tenantId() {
        TenantPrincipal p = HOLDER.get();
        return p == null ? null : p.tenantId();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static void runAs(TenantPrincipal principal, Runnable body) {
        TenantPrincipal previous = HOLDER.get();
        HOLDER.set(principal);
        try {
            body.run();
        } finally {
            if (previous == null) HOLDER.remove();
            else HOLDER.set(previous);
        }
    }

    /**
     * Value-returning sibling of {@link #runAs(TenantPrincipal, Runnable)}. The tenant
     * must be established BEFORE the transaction opens: TenantAwareTransactionManager
     * reads it in doBegin to set the RLS GUC, and Hibernate resolves a session's tenant
     * once at session-open and never re-reads it (see challenge #9).
     */
    public static <T> T runAs(TenantPrincipal principal, java.util.function.Supplier<T> body) {
        TenantPrincipal previous = HOLDER.get();
        HOLDER.set(principal);
        try {
            return body.get();
        } finally {
            if (previous == null) HOLDER.remove();
            else HOLDER.set(previous);
        }
    }
}
