package com.easycrm.catalog.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
}
