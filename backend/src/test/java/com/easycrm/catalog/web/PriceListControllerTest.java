package com.easycrm.catalog.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class PriceListControllerTest extends IntegrationTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    TestTokens tokens;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void createThenRejectDuplicateName() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String body = "{\"name\":\"Dealer\"}";
        mvc.perform(post("/api/v1/price-lists")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Dealer"));
        mvc.perform(post("/api/v1/price-lists")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }
}
