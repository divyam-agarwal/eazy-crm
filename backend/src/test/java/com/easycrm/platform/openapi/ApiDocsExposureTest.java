package com.easycrm.platform.openapi;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Outside the dev profile the springdoc routes must not be reachable. Two independent layers
 * produce that: springdoc's own enabled flags (false by default, so the routes are never
 * registered) and SecurityConfig's terminal denyAll(). The test suite does not run under the
 * dev profile, so this is the production posture.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiDocsExposureTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void apiDocsIsNotReachableOutsideDev() throws Exception {
        // 401 from SecurityConfig's HttpStatusEntryPoint, or 404 because springdoc never
        // registered the handler. Either is a correct "not exposed"; what must never happen is
        // a 200 carrying the document.
        int status = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getStatus();
        assertTrue(status == 401 || status == 404, "expected 401 or 404 outside dev, got " + status);
    }

    @Test
    void swaggerUiIsNotReachableOutsideDev() throws Exception {
        int status = mvc.perform(get("/swagger-ui/index.html"))
                .andReturn()
                .getResponse()
                .getStatus();
        assertTrue(status == 401 || status == 404, "expected 401 or 404 outside dev, got " + status);
    }

    @Test
    void healthIsStillReachable() throws Exception {
        // Guards against the new dev filter chain accidentally taking precedence over the
        // existing one: /actuator/health is permitAll today and must stay that way.
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
