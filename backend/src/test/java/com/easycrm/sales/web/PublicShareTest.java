package com.easycrm.sales.web;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import com.jayway.jsonpath.JsonPath;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PublicShareTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    private String sentQuotationId(String auth, String buyerName) throws Exception {
        String cId = JsonPath.read(mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"businessName":"%s","stateCode":"27","source":"MANUAL"}""".formatted(buyerName)))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String pId = JsonPath.read(mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"sku":"SKU-%s","name":"Widget","hsnCode":"84821011","uom":"PCS",
                     "gstRate":"18","baseRate":"100.00"}"""
                    .formatted(UUID.randomUUID().toString().substring(0, 8))))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String qId = JsonPath.read(mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(
                    "{\"customerId\":\"%s\",\"items\":[{\"productId\":\"%s\",\"qty\":\"10\"}]}"
                        .formatted(cId, pId)))
            .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth));
        return qId;
    }

    private String shareToken(String auth, String quotationId) throws Exception {
        String url = JsonPath.read(mvc.perform(post("/api/v1/quotations/" + quotationId + "/share")
            .header("Authorization", auth)).andReturn().getResponse().getContentAsString(),
            "$.publicUrl");
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private static String textOf(byte[] pdf) throws Exception {
        try (PDDocument doc = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void rendersTheQuotationWithNoAuthorizationHeaderAtAll() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String token = shareToken(auth, sentQuotationId(auth, "Bharat Industries"));
        TenantContext.clear();

        byte[] pdf = mvc.perform(get("/public/q/" + token))   // deliberately no header
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "application/pdf"))
            .andReturn().getResponse().getContentAsByteArray();

        // The tenant came from the share_link row, not from a JWT.
        assertTrue(textOf(pdf).contains("Bharat Industries"), textOf(pdf));
    }

    @Test
    void anUnknownTokenReturns404AndNotA401() throws Exception {
        // 401 would prove the route is auth-gated and leak that the token space exists;
        // 404 matches the codebase's cross-tenant rule.
        mvc.perform(get("/public/q/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/public/q/not-a-real-token")).andExpect(status().isNotFound());
    }

    @Test
    void oneTenantsTokenNeverRendersAnothersQuotation() throws Exception {
        String authA = "Bearer " + tokens.provisionOwner("27").token();
        String tokenA = shareToken(authA, sentQuotationId(authA, "Tenant A Buyer"));
        TenantContext.clear();

        String authB = "Bearer " + tokens.provisionOwner("29").token();
        sentQuotationId(authB, "Tenant B Buyer");
        TenantContext.clear();

        String text = textOf(mvc.perform(get("/public/q/" + tokenA))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());

        assertTrue(text.contains("Tenant A Buyer"), text);
        assertFalse(text.contains("Tenant B Buyer"), text);
    }

    @Test
    void anAlreadySharedVersionKeepsRenderingAfterTheQuotationIsRevised() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String qId = sentQuotationId(auth, "Bharat Industries");
        String v1Token = shareToken(auth, qId);

        mvc.perform(post("/api/v1/quotations/" + qId + "/revise").header("Authorization", auth));
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth));
        String v2Token = shareToken(auth, qId);
        TenantContext.clear();

        assertNotEquals(v1Token, v2Token);
        // The customer who received the v1 link still sees exactly what they were sent.
        assertTrue(textOf(mvc.perform(get("/public/q/" + v1Token))
            .andReturn().getResponse().getContentAsByteArray()).contains("(v1)"));
        assertTrue(textOf(mvc.perform(get("/public/q/" + v2Token))
            .andReturn().getResponse().getContentAsByteArray()).contains("(v2)"));
    }
}
