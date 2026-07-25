package com.easycrm.demo;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.tenant.Tenant;
import com.easycrm.tenant.TenantRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** SYNTHETIC demo data only. GSTINs (if added later) are checksum-valid but fabricated. */
@Component
@Profile("dev")
public class DemoSeeder implements CommandLineRunner {

    private final TenantRepository tenants;
    private final DemoRecordRepository records;

    public DemoSeeder(TenantRepository tenants, DemoRecordRepository records) {
        this.tenants = tenants;
        this.records = records;
    }

    @Override
    public void run(String... args) { seed(); }

    public void seed() {
        if (tenants.findBySlug("alpha-traders").isPresent()) return; // idempotent
        Tenant a = tenants.save(new Tenant("alpha-traders", "Alpha Traders (SYNTHETIC)", "27"));
        Tenant b = tenants.save(new Tenant("bravo-distributors", "Bravo Distributors (SYNTHETIC)", "29"));
        seedRecordsFor(a.getId(), "Alpha");
        seedRecordsFor(b.getId(), "Bravo");
    }

    private void seedRecordsFor(UUID tenantId, String prefix) {
        // Each save runs in its own transaction where TenantAwareTransactionManager sets
        // app.current_tenant from this context, so RLS WITH CHECK passes on insert.
        TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, UUID.randomUUID(), "OWNER"),
            () -> {
                records.save(new DemoRecord(prefix + " confidential record 1"));
                records.save(new DemoRecord(prefix + " confidential record 2"));
            });
    }
}
