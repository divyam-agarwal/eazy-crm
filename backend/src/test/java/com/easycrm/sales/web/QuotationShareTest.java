package com.easycrm.sales.web;

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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class QuotationShareTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    private String customer(String auth, String state) throws Exception {
        return JsonPath.read(mvc.perform(post("/api/v1/customers").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"businessName":"Bharat Industries","stateCode":"%s","source":"MANUAL"}"""
                    .formatted(state)))
            .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String sentQuotation(String auth, String customerId) throws Exception {
        String pId = JsonPath.read(mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"sku":"SKU-%s","name":"Widget","hsnCode":"84821011","uom":"PCS",
                     "gstRate":"18","baseRate":"100.00"}"""
                    .formatted(UUID.randomUUID().toString().substring(0, 8))))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String qId = JsonPath.read(mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(
                    "{\"customerId\":\"%s\",\"items\":[{\"productId\":\"%s\",\"qty\":\"10\"}]}"
                        .formatted(customerId, pId)))
            .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth));
        return qId;
    }

    private void addContact(String auth, String customerId, String phone, String whatsapp)
            throws Exception {
        mvc.perform(post("/api/v1/customers/" + customerId + "/contacts")
            .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Ramesh","phone":%s,"whatsappNumber":%s,"isPrimary":true}"""
                .formatted(phone == null ? "null" : "\"" + phone + "\"",
                           whatsapp == null ? "null" : "\"" + whatsapp + "\"")));
    }

    @Test
    void sharingTwiceReturnsTheSameLink() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String qId = sentQuotation(auth, customer(auth, "27"));

        String first = JsonPath.read(mvc.perform(post("/api/v1/quotations/" + qId + "/share")
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(), "$.publicUrl");
        String second = JsonPath.read(mvc.perform(post("/api/v1/quotations/" + qId + "/share")
                .header("Authorization", auth))
            .andReturn().getResponse().getContentAsString(), "$.publicUrl");

        // A link already WhatsApped to a customer must not stop working because
        // someone pressed share again.
        assertEquals(first, second);
    }

    @Test
    void theWaMeLinkPrefersTheWhatsappNumberOverThePhone() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String cId = customer(auth, "27");
        addContact(auth, cId, "+919000000001", "+919876543210");
        String qId = sentQuotation(auth, cId);

        String waMe = JsonPath.read(mvc.perform(post("/api/v1/quotations/" + qId + "/share")
            .header("Authorization", auth)).andReturn().getResponse().getContentAsString(),
            "$.waMeUrl");

        assertTrue(waMe.startsWith("https://wa.me/919876543210?text="), waMe);
        assertFalse(waMe.contains("919000000001"), waMe);
        assertTrue(waMe.contains("%2F"), waMe);   // the public URL's slashes are encoded
    }

    @Test
    void fallsBackToThePlainPhoneWhenThereIsNoWhatsappNumber() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String cId = customer(auth, "27");
        addContact(auth, cId, "+919000000001", null);
        String qId = sentQuotation(auth, cId);

        String waMe = JsonPath.read(mvc.perform(post("/api/v1/quotations/" + qId + "/share")
            .header("Authorization", auth)).andReturn().getResponse().getContentAsString(),
            "$.waMeUrl");

        assertTrue(waMe.startsWith("https://wa.me/919000000001?text="), waMe);
    }

    @Test
    void aCustomerWithNoContactStillGetsAShareableLink() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String qId = sentQuotation(auth, customer(auth, "27"));   // no contact at all

        String waMe = JsonPath.read(mvc.perform(post("/api/v1/quotations/" + qId + "/share")
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(), "$.waMeUrl");

        // No number: WhatsApp opens its own contact picker. Never block the share.
        assertTrue(waMe.startsWith("https://wa.me/?text="), waMe);
    }

    @Test
    void aDraftCannotBeShared() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String cId = customer(auth, "27");
        String pId = JsonPath.read(mvc.perform(post("/api/v1/products").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"sku":"SKU-%s","name":"Widget","hsnCode":"84821011","uom":"PCS",
                     "gstRate":"18","baseRate":"100.00"}"""
                    .formatted(UUID.randomUUID().toString().substring(0, 8))))
            .andReturn().getResponse().getContentAsString(), "$.id");
        String qId = JsonPath.read(mvc.perform(post("/api/v1/quotations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(
                    "{\"customerId\":\"%s\",\"items\":[{\"productId\":\"%s\",\"qty\":\"1\"}]}"
                        .formatted(cId, pId)))
            .andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(post("/api/v1/quotations/" + qId + "/share").header("Authorization", auth))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void crossTenantShareReturns404() throws Exception {
        String authA = "Bearer " + tokens.provisionOwner("27").token();
        String qId = sentQuotation(authA, customer(authA, "27"));

        String authB = "Bearer " + tokens.provisionOwner("29").token();
        mvc.perform(post("/api/v1/quotations/" + qId + "/share").header("Authorization", authB))
            .andExpect(status().isNotFound());
    }
}
