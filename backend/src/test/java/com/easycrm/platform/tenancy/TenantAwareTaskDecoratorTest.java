package com.easycrm.platform.tenancy;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class TenantAwareTaskDecoratorTest {

    @Test
    void contextPropagatesToWorkerThread() throws Exception {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(tenant, UUID.randomUUID(), "OWNER"));

        TenantAwareTaskDecorator decorator = new TenantAwareTaskDecorator();
        Executor pool = Executors.newSingleThreadExecutor();

        CompletableFuture<UUID> seen = new CompletableFuture<>();
        pool.execute(decorator.decorate(() -> seen.complete(TenantContext.tenantId())));

        assertEquals(tenant, seen.get(), "worker thread sees the submitter's tenant");
        TenantContext.clear();
    }

    @Test
    void workerThreadContextClearedAfterRun() throws Exception {
        TenantAwareTaskDecorator decorator = new TenantAwareTaskDecorator();
        Executor pool = Executors.newSingleThreadExecutor();

        TenantContext.set(new TenantContext.TenantPrincipal(UUID.randomUUID(), UUID.randomUUID(), "OWNER"));
        pool.execute(decorator.decorate(() -> {}));
        Thread.sleep(50);

        CompletableFuture<UUID> after = new CompletableFuture<>();
        pool.execute(() -> after.complete(TenantContext.tenantId()));
        assertNull(after.get(), "no tenant leaked into the pooled worker thread");
        TenantContext.clear();
    }
}
