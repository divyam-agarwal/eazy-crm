package com.easycrm.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class DemoRecordControllerTest extends IntegrationTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    DemoRecordRepository records;

    @Autowired
    TestTokens tokens;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void ownerCanReadOwnRecord() throws Exception {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(tenant, UUID.randomUUID(), "OWNER"));
        DemoRecord saved = records.saveAndFlush(new DemoRecord("mine"));
        TenantContext.clear();

        mvc.perform(get("/api/v1/demo-records/" + saved.getId())
                        .header("Authorization", "Bearer " + tokens.owner(tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("mine"));
    }
}
