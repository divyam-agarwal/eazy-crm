package com.easycrm.platform.money;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MoneyWireFormatTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void bigDecimalSerializesAsQuotedString() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String create = """
            {"sku":"SKU-MONEY","name":"Bolt","hsnCode":"7318","uom":"PCS",
             "gstRate":"18","baseRate":"12.50"}""";
        String body = mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(create))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        String getBody = mvc.perform(get("/api/v1/products/" + id).header("Authorization", auth))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // Raw-JSON assertion: the money fields must be JSON strings, not numbers.
        // gstRate is NUMERIC(18,4) in Postgres (V9__product.sql), so a persisted-then-reread
        // value always carries scale 4 ("18.0000") regardless of serialization format.
        assertThat(getBody).contains("\"baseRate\":\"12.50\"");
        assertThat(getBody).contains("\"gstRate\":\"18.0000\"");
    }
}
