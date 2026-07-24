package com.easycrm.platform.tenancy;

import org.springframework.core.task.TaskDecorator;

public class TenantAwareTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable runnable) {
        TenantContext.TenantPrincipal captured = TenantContext.get().orElse(null);
        return () -> {
            if (captured != null) TenantContext.set(captured);
            try {
                runnable.run();
            } finally {
                TenantContext.clear();
            }
        };
    }
}
