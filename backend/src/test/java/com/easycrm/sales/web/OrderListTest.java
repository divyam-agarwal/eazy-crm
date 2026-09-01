package com.easycrm.sales.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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

@SpringBootTest
@AutoConfigureMockMvc
class OrderListTest extends IntegrationTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    TestTokens tokens;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private String createCustomer(String auth, String name) throws Exception {
        return JsonPath.read(
                mvc.perform(post("/api/v1/customers")
                                .header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"businessName\":\"%s\",\"stateCode\":\"27\",\"source\":\"MANUAL\"}"
                                        .formatted(name)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
    }

    private String createProduct(String auth) throws Exception {
        return JsonPath.read(
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
    }

    /** One quotation per order — sales_order has UNIQUE(tenant_id, quotation_id). */
    private String createOrderFor(String auth, String customerId, String productId) throws Exception {
        String qId = JsonPath.read(
                mvc.perform(post("/api/v1/quotations")
                                .header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"customerId\":\"%s\",\"items\":[{\"productId\":\"%s\",\"qty\":\"1\"}]}"
                                        .formatted(customerId, productId)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
        mvc.perform(post("/api/v1/quotations/" + qId + "/send").header("Authorization", auth))
                .andExpect(status().isOk());
        return JsonPath.read(
                mvc.perform(post("/api/v1/quotations/" + qId + "/accept")
                                .header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
    }

    @Test
    void twoFiltersCombineInsteadOfDroppingOne() throws Exception {
        // Regression guard for challenge #24: the old if/else if dropped customerId
        // whenever status was also supplied.
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String p = createProduct(auth);
        String c1 = createCustomer(auth, "Alpha");
        String c2 = createCustomer(auth, "Beta");

        String a = createOrderFor(auth, c1, p); // c1, CONFIRMED  <- the only match
        createOrderFor(auth, c2, p); // c2, CONFIRMED
        String c = createOrderFor(auth, c1, p); // c1, DISPATCHED
        mvc.perform(post("/api/v1/orders/" + c + "/dispatch").header("Authorization", auth))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/orders")
                        .header("Authorization", auth)
                        .param("status", "CONFIRMED")
                        .param("customerId", c1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(a));
    }

    @Test
    void eachFilterWorksOnItsOwn() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String p = createProduct(auth);
        String c1 = createCustomer(auth, "Alpha");
        String c2 = createCustomer(auth, "Beta");

        createOrderFor(auth, c1, p);
        String b = createOrderFor(auth, c2, p);
        String c = createOrderFor(auth, c1, p);
        mvc.perform(post("/api/v1/orders/" + c + "/dispatch").header("Authorization", auth))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/orders").header("Authorization", auth).param("status", "DISPATCHED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(c));

        mvc.perform(get("/api/v1/orders").header("Authorization", auth).param("customerId", c2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(b));
    }

    @Test
    void noFiltersReturnsEveryOrderInTheTenant() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String p = createProduct(auth);
        String c1 = createCustomer(auth, "Alpha");
        createOrderFor(auth, c1, p);
        createOrderFor(auth, c1, p);

        mvc.perform(get("/api/v1/orders").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void crossTenantListIsEmpty() throws Exception {
        // The list now goes through orders.findAll(OrderSpecifications.filter(...), pageable) --
        // a new query path that adds no tenant predicate of its own (by design: @TenantId + RLS
        // handle it). This guards against a future regression, e.g. a "helpful" join or predicate
        // that bypasses RLS.
        String authA = "Bearer " + tokens.provisionOwner("27").token();
        String p = createProduct(authA);
        String c1 = createCustomer(authA, "Alpha");
        createOrderFor(authA, c1, p);

        String authB = "Bearer " + tokens.provisionOwner("29").token();
        mvc.perform(get("/api/v1/orders").header("Authorization", authB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }
}
