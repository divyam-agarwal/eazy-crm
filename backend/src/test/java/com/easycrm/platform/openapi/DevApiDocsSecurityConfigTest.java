package com.easycrm.platform.openapi;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The only test in this suite that runs under the dev profile, and therefore the only one in
 * which {@link DevApiDocsSecurityConfig} exists at all — everywhere else that bean is
 * {@code @Profile("dev")}-excluded, so nothing outside this class can say anything about it.
 * ApiDocsExposureTest, despite the name, tests the profile where the chain is absent.
 *
 * <p>Two assertions, and they are the two halves of what an {@code @Order(0)} filter chain has to
 * get right:
 *
 * <ul>
 *   <li>the dev chain works — /v3/api-docs is actually reachable, which nothing had ever
 *       demonstrated; the exposure story was three tests deep on "it is closed" and zero deep on
 *       "it opens";
 *   <li>the dev chain is narrow — an unauthenticated /api/v1/customers still gets 401. A chain at
 *       {@code @Order(0)} wins outright for every path its securityMatcher covers, so a matcher
 *       widened by accident (to {@code /**}, say) would silently make the whole API public in dev
 *       with its {@code anyRequest().permitAll()}. This assertion is the thing that goes red.
 * </ul>
 *
 * <p>Deliberately one class: {@code @ActiveProfiles("dev")} is a distinct context cache key, so
 * this costs a second Spring context in a suite that otherwise shares exactly one. Two
 * assertions justify that; a third class would not.
 */
@SpringBootTest
@ActiveProfiles("dev")
@AutoConfigureMockMvc
class DevApiDocsSecurityConfigTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void apiDocsIsReachableUnderDev() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void theDevChainDoesNotOpenUpTheRealApi() throws Exception {
        mvc.perform(get("/api/v1/customers")).andExpect(status().isUnauthorized());
    }
}
