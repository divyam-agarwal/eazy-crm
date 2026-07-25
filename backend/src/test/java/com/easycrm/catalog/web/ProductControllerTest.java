package com.easycrm.catalog.web;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ProductControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void createThenGet() throws Exception {
        UUID tenant = UUID.randomUUID();
        String auth = "Bearer " + tokens.owner(tenant);
        String create = """
            {"sku":"SKU-9","name":"Bolt","hsnCode":"7318","uom":"PCS",
             "gstRate":"18","baseRate":"12.50"}""";

        String body = mvc.perform(post("/api/v1/products")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(create))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.sku").value("SKU-9"))
            .andExpect(jsonPath("$.active").value(true))
            .andReturn().getResponse().getContentAsString();

        String id = JsonPath.read(body, "$.id");
        mvc.perform(get("/api/v1/products/" + id).header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Bolt"));
    }

    @Test
    void rejectsDisallowedGstRateWith422() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String create = """
            {"sku":"SKU-BAD","name":"X","hsnCode":"7318","uom":"PCS",
             "gstRate":"7","baseRate":"1.00"}""";
        mvc.perform(post("/api/v1/products")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(create))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error.fields.gstRate").exists());
    }

    @Test
    void duplicateSkuReturns409() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String create = """
            {"sku":"SKU-DUP2","name":"X","hsnCode":"7318","uom":"PCS",
             "gstRate":"18","baseRate":"1.00"}""";
        mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(create))
            .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(create))
            .andExpect(status().isConflict());
    }
}
