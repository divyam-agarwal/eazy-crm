package com.easycrm.platform.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Outside the dev profile the springdoc routes must not be reachable. Two independent layers
 * produce that, and this class covers each separately.
 *
 * <p>{@link #apiDocsIsNotReachableOutsideDev()} and {@link #swaggerUiIsNotReachableOutsideDev()}
 * cover layer 2: {@code SecurityConfig}'s terminal {@code denyAll()} runs in the
 * {@code AuthorizationFilter}, ahead of {@code DispatcherServlet} routing, so a denied request
 * always comes back 401 regardless of whether springdoc registered a handler for the path --
 * there is no route-resolution step left to produce a 404. The test suite does not run under
 * the dev profile, so this is the production posture.
 *
 * <p>{@link #apiDocsResourceBeanIsAbsentOutsideDev()} covers layer 1 directly: springdoc's
 * {@code OpenApiWebMvcResource} controller bean is registered by an auto-configuration class
 * gated with {@code @ConditionalOnProperty(name = "springdoc.api-docs.enabled", matchIfMissing =
 * true)}, so with the flag off by default that bean is never added to the context -- independent
 * of anything Spring Security does. Flip {@code springdoc.api-docs.enabled} back to {@code true}
 * and this assertion fails, which is what makes it a real test of layer 1 rather than a restated
 * assertion about layer 2.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiDocsExposureTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext applicationContext;

    @Test
    void apiDocsIsNotReachableOutsideDev() throws Exception {
        // 401 from SecurityConfig's HttpStatusEntryPoint: the AuthorizationFilter's denyAll()
        // rejects the request before DispatcherServlet ever resolves a handler for it, so a 404
        // cannot occur here regardless of whether springdoc registered the route.
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
    }

    @Test
    void swaggerUiIsNotReachableOutsideDev() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
    }

    @Test
    void apiDocsResourceBeanIsAbsentOutsideDev() {
        // Direct test of layer 1: with springdoc.api-docs.enabled=false (the default), springdoc
        // never registers its api-docs controller bean at all, regardless of what SecurityConfig
        // would otherwise do with a request for it.
        assertEquals(
                0,
                applicationContext.getBeanNamesForType(OpenApiWebMvcResource.class).length,
                "springdoc's api-docs resource bean must not be registered outside dev");
    }

    @Test
    void healthIsStillReachable() throws Exception {
        // /actuator/health is permitAll today and must stay that way. Note what this does NOT
        // check: the dev filter chain is @Profile("dev") and this class does not activate it, so
        // this assertion is identical whether DevApiDocsSecurityConfig exists, is deleted, or has
        // its securityMatcher widened to "/**". DevApiDocsSecurityConfigTest is what covers that.
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
