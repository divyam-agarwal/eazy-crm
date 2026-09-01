package com.easycrm.sales.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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

@SpringBootTest
@AutoConfigureMockMvc
class EnquiryStateMachineTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestTokens tokens;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private String create(String auth, String phone) throws Exception {
        return JsonPath.read(
                mvc.perform(post("/api/v1/enquiries")
                                .header("Authorization", auth)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"contactName\":\"Ravi\",\"contactPhone\":\"%s\",\"source\":\"PHONE\"}"
                                        .formatted(phone)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.id");
    }

    @Test
    void advanceSkipThenLoseAndTerminalGuards() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String id = create(auth, "9876543210");

        mvc.perform(post("/api/v1/enquiries/" + id + "/advance")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"QUALIFIED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("QUALIFIED"));

        // backward -> 422
        mvc.perform(post("/api/v1/enquiries/" + id + "/advance")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"CONTACTED\"}"))
                .andExpect(status().isUnprocessableEntity());

        // lose -> terminal
        mvc.perform(post("/api/v1/enquiries/" + id + "/lose")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lostReason\":\"gone\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("LOST"))
                .andExpect(jsonPath("$.lostReason").value("gone"));

        // advancing a terminal enquiry -> 422
        mvc.perform(post("/api/v1/enquiries/" + id + "/advance")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"QUALIFIED\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void loseWithBlankReasonIs400() throws Exception {
        // @NotBlank on the request body -> MethodArgumentNotValid -> 400 (bean validation
        // runs before the service). This is the bean-validation guard; the entity guard
        // (422) is the defence-in-depth backstop covered in EnquiryTest.
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String id = create(auth, "9876543211");
        mvc.perform(post("/api/v1/enquiries/" + id + "/lose")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lostReason\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void editUpdatesFieldsAndBlocksOnTerminal() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        String id = create(auth, "9876543212");

        mvc.perform(patch("/api/v1/enquiries/" + id)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {"contactName":"Ravi Kumar","contactPhone":"9876543212",
                     "source":"REFERRAL","expectedValue":"12000.00"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contactName").value("Ravi Kumar"))
                .andExpect(jsonPath("$.source").value("REFERRAL"))
                .andExpect(jsonPath("$.expectedValue").value("12000.00"));

        mvc.perform(post("/api/v1/enquiries/" + id + "/lose")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lostReason\":\"gone\"}"))
                .andExpect(status().isOk());

        // editing a terminal enquiry -> 422
        mvc.perform(patch("/api/v1/enquiries/" + id)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {"contactName":"Nope","contactPhone":"9876543212","source":"PHONE"}"""))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void editingPhoneIntoAnotherActiveEnquiryConflicts() throws Exception {
        String auth = "Bearer " + tokens.provisionOwner("27").token();
        create(auth, "9111111111"); // occupies phone A
        String id2 = create(auth, "9222222222"); // to be edited onto A
        mvc.perform(patch("/api/v1/enquiries/" + id2)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {"contactName":"Ravi","contactPhone":"9111111111","source":"PHONE"}"""))
                .andExpect(status().isConflict());
    }
}
