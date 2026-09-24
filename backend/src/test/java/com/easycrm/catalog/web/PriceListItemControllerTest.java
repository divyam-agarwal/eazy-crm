package com.easycrm.catalog.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.easycrm.catalog.PriceList;
import com.easycrm.catalog.PriceListRepository;
import com.easycrm.catalog.Product;
import com.easycrm.catalog.ProductRepository;
import com.easycrm.catalog.Uom;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
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
class PriceListItemControllerTest extends IntegrationTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    TestTokens tokens;

    @Autowired
    PriceListRepository priceLists;

    @Autowired
    ProductRepository products;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private record Fixture(UUID tenant, UUID priceListId, UUID productId) {}

    private Fixture seed() {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(tenant, UUID.randomUUID(), "OWNER"));
        PriceList pl = priceLists.saveAndFlush(new PriceList("Dealer"));
        Product p = products.saveAndFlush(
                new Product("SKU-PLI", "Widget", "7318", Uom.PCS, new BigDecimal("18.0000"), new BigDecimal("100.00")));
        TenantContext.clear();
        return new Fixture(tenant, pl.getId(), p.getId());
    }

    @Test
    void addItemWithOverrideRate() throws Exception {
        Fixture f = seed();
        String auth = "Bearer " + tokens.owner(f.tenant());
        String body = "{\"productId\":\"" + f.productId() + "\",\"overrideRate\":\"95.00\"}";
        mvc.perform(post("/api/v1/price-lists/" + f.priceListId() + "/items")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productId").value(f.productId().toString()))
                .andExpect(jsonPath("$.overrideRate").exists());
    }

    @Test
    void rejectsBothRateAndDiscountWith422() throws Exception {
        Fixture f = seed();
        String auth = "Bearer " + tokens.owner(f.tenant());
        String body = "{\"productId\":\"" + f.productId() + "\",\"overrideRate\":\"95.00\",\"discountPct\":\"10.0\"}";
        mvc.perform(post("/api/v1/price-lists/" + f.priceListId() + "/items")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.overrideRate").exists());
    }

    @Test
    void negativeOverrideRateReturns422() throws Exception {
        Fixture f = seed();
        String auth = "Bearer " + tokens.owner(f.tenant());
        String body = "{\"productId\":\"" + f.productId() + "\",\"overrideRate\":\"-5.00\"}";
        mvc.perform(post("/api/v1/price-lists/" + f.priceListId() + "/items")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.overrideRate").exists());
    }

    @Test
    void discountOver100Returns422() throws Exception {
        Fixture f = seed();
        String auth = "Bearer " + tokens.owner(f.tenant());
        String body = "{\"productId\":\"" + f.productId() + "\",\"discountPct\":\"150.0\"}";
        mvc.perform(post("/api/v1/price-lists/" + f.priceListId() + "/items")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.discountPct").exists());
    }

    @Test
    void addItemToUnknownPriceListReturns404() throws Exception {
        Fixture f = seed();
        String auth = "Bearer " + tokens.owner(f.tenant());
        String body = "{\"productId\":\"" + f.productId() + "\",\"overrideRate\":\"95.00\"}";
        mvc.perform(post("/api/v1/price-lists/" + UUID.randomUUID() + "/items")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsNeitherRateNorDiscountWith422() throws Exception {
        Fixture f = seed();
        String auth = "Bearer " + tokens.owner(f.tenant());
        String body = "{\"productId\":\"" + f.productId() + "\"}";
        mvc.perform(post("/api/v1/price-lists/" + f.priceListId() + "/items")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fields.overrideRate").exists());
    }

    @Test
    void duplicateProductInPriceListReturns409() throws Exception {
        Fixture f = seed();
        String auth = "Bearer " + tokens.owner(f.tenant());
        String body = "{\"productId\":\"" + f.productId() + "\",\"overrideRate\":\"95.00\"}";
        mvc.perform(post("/api/v1/price-lists/" + f.priceListId() + "/items")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/price-lists/" + f.priceListId() + "/items")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void deleteItemUnderWrongPriceListReturns404() throws Exception {
        Fixture f = seed();
        String auth = "Bearer " + tokens.owner(f.tenant());
        String body = "{\"productId\":\"" + f.productId() + "\",\"overrideRate\":\"95.00\"}";
        String created = mvc.perform(post("/api/v1/price-lists/" + f.priceListId() + "/items")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String itemId = JsonPath.read(created, "$.id");

        // A second price list under the SAME tenant; the item does not belong to it.
        TenantContext.set(new TenantContext.TenantPrincipal(f.tenant(), UUID.randomUUID(), "OWNER"));
        UUID otherPriceListId = priceLists.saveAndFlush(new PriceList("Retail")).getId();
        TenantContext.clear();

        mvc.perform(delete("/api/v1/price-lists/" + otherPriceListId + "/items/" + itemId)
                        .header("Authorization", auth))
                .andExpect(status().isNotFound());
    }

    @Test
    void neitherRateNorDiscountCarriesTheXorCode() throws Exception {
        Fixture f = seed();
        String auth = "Bearer " + tokens.owner(f.tenant());
        mvc.perform(post("/api/v1/price-lists/" + f.priceListId() + "/items")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + f.productId() + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fieldCodes.overrideRate").value("RATE_RULE_XOR"));
    }

    @Test
    void bothRateAndDiscountCarriesTheSameXorCode() throws Exception {
        Fixture f = seed();
        String auth = "Bearer " + tokens.owner(f.tenant());
        mvc.perform(post("/api/v1/price-lists/" + f.priceListId() + "/items")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + f.productId()
                                + "\",\"overrideRate\":\"10\",\"discountPct\":\"5\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fieldCodes.overrideRate").value("RATE_RULE_XOR"));
    }

    @Test
    void discountOutOfRangeCarriesItsOwnCode() throws Exception {
        Fixture f = seed();
        String auth = "Bearer " + tokens.owner(f.tenant());
        mvc.perform(post("/api/v1/price-lists/" + f.priceListId() + "/items")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + f.productId() + "\",\"discountPct\":\"101\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fieldCodes.discountPct").value("DISCOUNT_PCT_RANGE"));
    }

    @Test
    void negativeOverrideRateCarriesItsOwnCode() throws Exception {
        Fixture f = seed();
        String auth = "Bearer " + tokens.owner(f.tenant());
        mvc.perform(post("/api/v1/price-lists/" + f.priceListId() + "/items")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + f.productId() + "\",\"overrideRate\":\"-1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fieldCodes.overrideRate").value("OVERRIDE_RATE_NEGATIVE"));
    }

    @Test
    void duplicateProductInListCarriesAFieldAndACode() throws Exception {
        Fixture f = seed();
        String auth = "Bearer " + tokens.owner(f.tenant());
        mvc.perform(post("/api/v1/price-lists/" + f.priceListId() + "/items")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + f.productId() + "\",\"overrideRate\":\"10\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/price-lists/" + f.priceListId() + "/items")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + f.productId() + "\",\"overrideRate\":\"12\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.fieldCodes.productId").value("PRODUCT_DUPLICATE"));
    }

    private String createItem(Fixture f, String auth, String body) throws Exception {
        String created = mvc.perform(post("/api/v1/price-lists/" + f.priceListId() + "/items")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(created, "$.id");
    }

    @Test
    void updatesTheRateInOneRequest() throws Exception {
        Fixture f = seed();
        String auth = "Bearer " + tokens.owner(f.tenant());
        String itemId = createItem(f, auth, "{\"productId\":\"" + f.productId() + "\",\"overrideRate\":\"10\"}");

        mvc.perform(put("/api/v1/price-lists/" + f.priceListId() + "/items/" + itemId)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overrideRate\":\"15.50\"}"))
                .andExpect(status().isOk())
                // Money is a JSON string on the wire, and 15.50 must not come back as 15.5.
                .andExpect(jsonPath("$.overrideRate").value("15.50"));
    }

    @Test
    void switchesFromRateToDiscount() throws Exception {
        Fixture f = seed();
        String auth = "Bearer " + tokens.owner(f.tenant());
        String itemId = createItem(f, auth, "{\"productId\":\"" + f.productId() + "\",\"overrideRate\":\"10\"}");

        mvc.perform(put("/api/v1/price-lists/" + f.priceListId() + "/items/" + itemId)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"discountPct\":\"12\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discountPct").value("12"))
                // The XOR is an invariant of the stored row, not just of the request: the old
                // overrideRate must be cleared, or the row now violates it.
                .andExpect(jsonPath("$.overrideRate").doesNotExist());
    }

    @Test
    void updateEnforcesTheXorRule() throws Exception {
        Fixture f = seed();
        String auth = "Bearer " + tokens.owner(f.tenant());
        String itemId = createItem(f, auth, "{\"productId\":\"" + f.productId() + "\",\"overrideRate\":\"10\"}");

        mvc.perform(put("/api/v1/price-lists/" + f.priceListId() + "/items/" + itemId)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overrideRate\":\"10\",\"discountPct\":\"5\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fieldCodes.overrideRate").value("RATE_RULE_XOR"));
    }

    @Test
    void updateEnforcesTheRangeRules() throws Exception {
        Fixture f = seed();
        String auth = "Bearer " + tokens.owner(f.tenant());
        String itemId = createItem(f, auth, "{\"productId\":\"" + f.productId() + "\",\"overrideRate\":\"10\"}");

        mvc.perform(put("/api/v1/price-lists/" + f.priceListId() + "/items/" + itemId)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"discountPct\":\"101\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fieldCodes.discountPct").value("DISCOUNT_PCT_RANGE"));
    }

    @Test
    void updateRejectsAnItemBelongingToAnotherPriceList() throws Exception {
        Fixture f = seed();
        String auth = "Bearer " + tokens.owner(f.tenant());
        String itemId = createItem(f, auth, "{\"productId\":\"" + f.productId() + "\",\"overrideRate\":\"10\"}");

        // A second price list under the SAME tenant; the item does not belong to it.
        TenantContext.set(new TenantContext.TenantPrincipal(f.tenant(), UUID.randomUUID(), "OWNER"));
        UUID otherPriceListId = priceLists.saveAndFlush(new PriceList("Retail")).getId();
        TenantContext.clear();

        mvc.perform(put("/api/v1/price-lists/" + otherPriceListId + "/items/" + itemId)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overrideRate\":\"1\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateIsInvisibleAcrossTenants() throws Exception {
        Fixture f = seed();
        String auth = "Bearer " + tokens.owner(f.tenant());
        String itemId = createItem(f, auth, "{\"productId\":\"" + f.productId() + "\",\"overrideRate\":\"10\"}");

        String otherTenantAuth = "Bearer " + tokens.owner(UUID.randomUUID());
        mvc.perform(put("/api/v1/price-lists/" + f.priceListId() + "/items/" + itemId)
                        .header("Authorization", otherTenantAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overrideRate\":\"1\"}"))
                .andExpect(status().isNotFound());
    }
}
