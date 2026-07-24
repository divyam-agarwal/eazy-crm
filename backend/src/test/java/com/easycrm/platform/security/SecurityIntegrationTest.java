package com.easycrm.platform.security;

import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityIntegrationTest extends IntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    void protectedRouteWithoutTokenIs401() throws Exception {
        mvc.perform(get("/api/v1/demo-records/00000000-0000-0000-0000-000000000000"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
