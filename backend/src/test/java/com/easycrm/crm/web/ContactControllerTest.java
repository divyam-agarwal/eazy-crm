package com.easycrm.crm.web;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.crm.CustomerSource;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
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
class ContactControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired TestTokens tokens;
    @Autowired CustomerRepository customers;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void addAndListContacts() throws Exception {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(tenant, UUID.randomUUID(), "OWNER"));
        Customer c = customers.saveAndFlush(new Customer("Acme", null, "27",
                                    null, null, 0, null, null, CustomerSource.MANUAL));
        TenantContext.clear();

        String auth = "Bearer " + tokens.owner(tenant);
        String add = """
            {"name":"Ravi","phone":"9876543210","isPrimary":true}""";
        mvc.perform(post("/api/v1/customers/" + c.getId() + "/contacts")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(add))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Ravi"));

        mvc.perform(get("/api/v1/customers/" + c.getId() + "/contacts").header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void addContactToUnknownCustomerReturns404() throws Exception {
        String auth = "Bearer " + tokens.owner(UUID.randomUUID());
        String add = "{\"name\":\"Ravi\"}";
        mvc.perform(post("/api/v1/customers/" + UUID.randomUUID() + "/contacts")
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(add))
            .andExpect(status().isNotFound());
    }
}
