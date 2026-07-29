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

    private void addContactWithPrimary(String auth, String customerId, String whatsapp,
                                       boolean isPrimary) throws Exception {
        mvc.perform(post("/api/v1/customers/" + customerId + "/contacts")
            .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"Contact","whatsappNumber":"%s","isPrimary":%s}"""
                .formatted(whatsapp, isPrimary)));
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

        // The text segment must be RFC 3986-encoded (%20 for space), not
        // form-urlencoded (+ for space) — a literal '+' renders as-is to the customer.
        String text = waMe.substring(waMe.indexOf("text=") + "text=".length());
        assertTrue(text.contains("%20"), waMe);
        assertFalse(text.contains("+"), waMe);
    }

    @Test
    void theWaMeLinkTargetsThePrimaryContactAmongSeveral() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String cId = customer(auth, "27");
        addContactWithPrimary(auth, cId, "919000000001", false);
        addContactWithPrimary(auth, cId, "919876543210", true);
        addContactWithPrimary(auth, cId, "919000000003", false);
        String qId = sentQuotation(auth, cId);

        String waMe = JsonPath.read(mvc.perform(post("/api/v1/quotations/" + qId + "/share")
            .header("Authorization", auth)).andReturn().getResponse().getContentAsString(),
            "$.waMeUrl");

        // Neither non-primary contact must be picked, regardless of insertion order.
        assertTrue(waMe.startsWith("https://wa.me/919876543210?text="), waMe);
    }

    @Test
    void withNoPrimaryContactTheRecipientChoiceIsStableAcrossRepeatedShares() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String cId = customer(auth, "27");
        addContactWithPrimary(auth, cId, "919000000001", false);
        addContactWithPrimary(auth, cId, "919000000002", false);
        String qId = sentQuotation(auth, cId);

        String first = JsonPath.read(mvc.perform(post("/api/v1/quotations/" + qId + "/share")
                .header("Authorization", auth))
            .andReturn().getResponse().getContentAsString(), "$.waMeUrl");
        String second = JsonPath.read(mvc.perform(post("/api/v1/quotations/" + qId + "/share")
                .header("Authorization", auth))
            .andReturn().getResponse().getContentAsString(), "$.waMeUrl");

        // No primary contact: the tie-break (oldest contact first) must be deterministic,
        // not an arbitrary pick that could vary between calls.
        assertEquals(first, second);
        assertTrue(first.startsWith("https://wa.me/919000000001?text="), first);
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
