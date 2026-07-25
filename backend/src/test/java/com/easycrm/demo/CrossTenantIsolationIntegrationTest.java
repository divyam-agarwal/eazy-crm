package com.easycrm.demo;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CrossTenantIsolationIntegrationTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DemoRecordRepository records;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    private UUID saveFor(UUID tenant, String label) {
        TenantContext.set(new TenantContext.TenantPrincipal(tenant, UUID.randomUUID(), "OWNER"));
        UUID id = records.saveAndFlush(new DemoRecord(label)).getId();
        TenantContext.clear();
        return id;
    }

    @Test
    void tenantACannotReadTenantBRecord_returns404NotFound() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID bRecordId = saveFor(tenantB, "b-secret");

        // Authenticated as A, requesting B's real record id -> must be 404, never 403.
        mvc.perform(get("/api/v1/demo-records/" + bRecordId)
                .header("Authorization", "Bearer " + tokens.owner(tenantA)))
           .andExpect(status().isNotFound());
    }
}
