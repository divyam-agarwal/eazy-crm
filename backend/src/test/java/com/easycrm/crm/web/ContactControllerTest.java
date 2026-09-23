package com.easycrm.crm.web;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.easycrm.crm.Customer;
import com.easycrm.crm.CustomerRepository;
import com.easycrm.crm.CustomerSource;
import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ContactControllerTest extends IntegrationTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    TestTokens tokens;

    @Autowired
    CustomerRepository customers;

    // Shared fixture for the validation/demotion tests below, which all operate against one
    // customer within one tenant. Tests that need their own tenant/customer wiring (cross-tenant,
    // cross-customer 404 checks) continue to set that up locally, as before.
    String auth;
    String customerId;

    @BeforeEach
    void setUpCustomer() throws Exception {
        auth = "Bearer " + tokens.owner(UUID.randomUUID());
        customerId = createCustomer(auth, "Acme");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void addAndListContacts() throws Exception {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(tenant, UUID.randomUUID(), "OWNER"));
        Customer c = customers.saveAndFlush(
                new Customer("Acme", null, "27", null, null, 0, null, null, CustomerSource.MANUAL));
        TenantContext.clear();

        String auth = "Bearer " + tokens.owner(tenant);
        String add = """
            {"name":"Ravi","phone":"9876543210","isPrimary":true}""";
        mvc.perform(post("/api/v1/customers/" + c.getId() + "/contacts")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(add))
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(add))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateContactUnderWrongCustomerReturns404() throws Exception {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(tenant, UUID.randomUUID(), "OWNER"));
        Customer c1 = customers.saveAndFlush(
                new Customer("C1", null, "27", null, null, 0, null, null, CustomerSource.MANUAL));
        Customer c2 = customers.saveAndFlush(
                new Customer("C2", null, "27", null, null, 0, null, null, CustomerSource.MANUAL));
        TenantContext.clear();

        String auth = "Bearer " + tokens.owner(tenant);
        String created = mvc.perform(post("/api/v1/customers/" + c1.getId() + "/contacts")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ravi\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String contactId = JsonPath.read(created, "$.id");

        // Update the contact via the WRONG customer (c2) -> 404
        mvc.perform(put("/api/v1/customers/" + c2.getId() + "/contacts/" + contactId)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ravi Updated\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteContactUnderWrongCustomerReturns404() throws Exception {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(tenant, UUID.randomUUID(), "OWNER"));
        Customer c1 = customers.saveAndFlush(
                new Customer("C1", null, "27", null, null, 0, null, null, CustomerSource.MANUAL));
        Customer c2 = customers.saveAndFlush(
                new Customer("C2", null, "27", null, null, 0, null, null, CustomerSource.MANUAL));
        TenantContext.clear();

        String auth = "Bearer " + tokens.owner(tenant);
        String created = mvc.perform(post("/api/v1/customers/" + c1.getId() + "/contacts")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ravi\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String contactId = JsonPath.read(created, "$.id");

        mvc.perform(delete("/api/v1/customers/" + c2.getId() + "/contacts/" + contactId)
                        .header("Authorization", auth))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsAMalformedEmail() throws Exception {
        // Bean validation, so this is a 400 with an automatic EMAIL code -- not a 422.
        mvc.perform(post("/api/v1/customers/" + customerId + "/contacts")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ramesh\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldCodes.email").value("EMAIL"));
    }

    @Test
    void rejectsAnOverlongPhone() throws Exception {
        mvc.perform(post("/api/v1/customers/" + customerId + "/contacts")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ramesh\",\"phone\":\"012345678901234567890\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldCodes.phone").value("SIZE"));
    }

    @Test
    void acceptsATwentyCharacterPhone() throws Exception {
        // Boundary in the allowed direction: the column is VARCHAR(20), so 20 must pass. A test
        // that only checks 21 fails cannot tell max=20 from max=19.
        mvc.perform(post("/api/v1/customers/" + customerId + "/contacts")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ramesh\",\"phone\":\"01234567890123456789\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void addingAPrimaryContactDemotesTheExistingOne() throws Exception {
        String first = createContact(auth, customerId, "Ramesh", true);
        String second = createContact(auth, customerId, "Suresh", true);

        mvc.perform(get("/api/v1/customers/" + customerId + "/contacts").header("Authorization", auth))
                .andExpect(status().isOk())
                // Exactly one primary, and it is the newest. Without demotion this is 2.
                // (Not "$[?(...)].length()": with json-path 2.10's default provider, .length()
                // after a filter reports each matched object's OWN key count, not the match
                // count -- hasSize() on the filtered array itself is the idiom that actually
                // counts matches.)
                .andExpect(jsonPath("$[?(@.isPrimary == true)]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.isPrimary == true)].name").value("Suresh"));
    }

    @Test
    void promotingViaUpdateDemotesTheExistingPrimary() throws Exception {
        String first = createContact(auth, customerId, "Ramesh", true);
        String second = createContact(auth, customerId, "Suresh", false);

        mvc.perform(put("/api/v1/customers/" + customerId + "/contacts/" + second)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Suresh\",\"isPrimary\":true}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/customers/" + customerId + "/contacts").header("Authorization", auth))
                .andExpect(jsonPath("$[?(@.isPrimary == true)]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.isPrimary == true)].name").value("Suresh"));
    }

    @Test
    void demotionDoesNotReachAnotherCustomersContacts() throws Exception {
        // The bug this prevents: demoting "every primary contact" instead of "every primary
        // contact of THIS customer" silently unsets a colleague's data, tenant-wide.
        String otherCustomerId = createCustomer(auth, "Second Firm");
        createContact(auth, otherCustomerId, "Untouched", true);
        createContact(auth, customerId, "Ramesh", true);

        mvc.perform(get("/api/v1/customers/" + otherCustomerId + "/contacts").header("Authorization", auth))
                .andExpect(jsonPath("$[?(@.isPrimary == true)]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.isPrimary == true)].name").value("Untouched"));
    }

    @Test
    void anUpdateThatDoesNotPromoteLeavesThePrimaryAlone() throws Exception {
        String first = createContact(auth, customerId, "Ramesh", true);
        String second = createContact(auth, customerId, "Suresh", false);

        mvc.perform(put("/api/v1/customers/" + customerId + "/contacts/" + second)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Suresh Kumar\",\"isPrimary\":false}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/customers/" + customerId + "/contacts").header("Authorization", auth))
                .andExpect(jsonPath("$[?(@.isPrimary == true)].name").value("Ramesh"));
    }

    /** Returns the created customer's id. */
    private String createCustomer(String auth, String businessName) throws Exception {
        String body = """
                {"businessName":"%s","stateCode":"27","source":"MANUAL"}""".formatted(businessName);
        String response = mvc.perform(post("/api/v1/customers")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    /** Returns the created contact's id. */
    private String createContact(String auth, String customerId, String name, boolean isPrimary) throws Exception {
        String body = """
                {"name":"%s","isPrimary":%s}""".formatted(name, isPrimary);
        String response = mvc.perform(post("/api/v1/customers/" + customerId + "/contacts")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.id");
    }
}
