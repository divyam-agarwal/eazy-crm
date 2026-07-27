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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class EnquiryListTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    private String create(String auth, String name, String phone, String source, String assignedTo)
            throws Exception {
        String assignedJson = assignedTo == null ? "" : ",\"assignedTo\":\"" + assignedTo + "\"";
        return JsonPath.read(mvc.perform(post("/api/v1/enquiries").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(
                    "{\"contactName\":\"%s\",\"contactPhone\":\"%s\",\"source\":\"%s\"%s}"
                        .formatted(name, phone, source, assignedJson)))
            .andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void getByIdReturnsEnquiry() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String id = create(auth, "Ravi", "9876543210", "PHONE", null);
        mvc.perform(get("/api/v1/enquiries/" + id).header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void twoFiltersCombineCorrectly() throws Exception {
        // Regression guard: order-list dropped one filter when two were supplied.
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String userA = UUID.randomUUID().toString();
        String userB = UUID.randomUUID().toString();
        create(auth, "A", "9000000001", "PHONE", userA);      // source=PHONE, assignee=A
        create(auth, "B", "9000000002", "WHATSAPP", userA);   // source=WHATSAPP, assignee=A
        create(auth, "C", "9000000003", "PHONE", userB);      // source=PHONE, assignee=B

        // source=PHONE AND assignedTo=A -> only the first
        mvc.perform(get("/api/v1/enquiries").header("Authorization", auth)
                .param("source", "PHONE").param("assignedTo", userA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].contactName").value("A"));
    }

    @Test
    void crossTenantGetReturns404AndListIsEmpty() throws Exception {
        String authA = "Bearer " + tokens.provisionOwner("27").token();
        String id = create(authA, "Ravi", "9876543210", "PHONE", null);

        String authB = "Bearer " + tokens.provisionOwner("29").token();
        mvc.perform(get("/api/v1/enquiries/" + id).header("Authorization", authB))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/enquiries").header("Authorization", authB))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0));
    }
}
