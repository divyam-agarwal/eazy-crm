package com.easycrm.sales.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.easycrm.iam.AuditLogRepository;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class QuotationAcceptAuditTest extends IntegrationTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    TestTokens tokens;

    @Autowired
    AuditLogRepository audits;

    @Autowired
    TransactionTemplate tx;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void acceptWritesQuotationAcceptedAuditRow() throws Exception {
        var owner = tokens.provisionOwner("27");
        String auth = "Bearer " + owner.token();

        // Minimal sent quotation.
        String cId = JsonPath.read(
                mvc.perform(post("/api/v1/customers")
                                .header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                    {"businessName":"Acme","stateCode":"27","source":"MANUAL"}"""))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        String pId = JsonPath.read(
                mvc.perform(post("/api/v1/products")
                                .header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                    {"sku":"SKU-%s","name":"W","hsnCode":"84818090","uom":"PCS","gstRate":"18","baseRate":"100.00"}""".formatted(
                                                UUID.randomUUID().toString().substring(0, 8))))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        String qId = JsonPath.read(
                mvc.perform(post("/api/v1/quotations")
                                .header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"customerId\":\"%s\",\"items\":[{\"productId\":\"%s\",\"qty\":\"1\"}]}"
                                        .formatted(cId, pId)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/quotations/" + qId + "/accept")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        // Count QUOTATION_ACCEPTED audit rows in this tenant (RLS-scoped read).
        TenantContext.set(new TenantContext.TenantPrincipal(owner.tenantId(), null, "OWNER"));
        long count = tx.execute(s -> audits.countByAction("QUOTATION_ACCEPTED"));
        assertThat(count).isEqualTo(1);
    }
}
