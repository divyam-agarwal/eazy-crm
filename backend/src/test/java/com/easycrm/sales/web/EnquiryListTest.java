package com.easycrm.sales.web;

import com.easycrm.iam.Role;
import com.easycrm.iam.User;
import com.easycrm.iam.UserRepository;
import com.easycrm.iam.UserStatus;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class EnquiryListTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;
    @Autowired UserRepository users;
    @Autowired TransactionTemplate tx;

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
        TestTokens.ProvisionedOwner owner = tokens.provisionOwner("27");
        String auth = "Bearer " + owner.token();
        // Real ACTIVE users, not bare random ids: EnquiryService now validates assignedTo
        // (task 7) -- a bare random UUID would 422 on create.
        String userA = seedUser(owner.tenantId(), UserStatus.ACTIVE).toString();
        String userB = seedUser(owner.tenantId(), UserStatus.ACTIVE).toString();
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

    /** Seeds a real User row in the given tenant so assignedTo can resolve against it. */
    private UUID seedUser(UUID tenantId, UserStatus status) {
        return TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, UUID.randomUUID(), "OWNER"),
            () -> tx.execute(s -> users.save(new User(
                "user-" + UUID.randomUUID() + "@example.com", null, "hash",
                Role.SALES_EXEC, status)).getId()));
    }
}
