package com.easycrm.platform.openapi;

import static org.junit.jupiter.api.Assertions.*;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;

class OpenApiConfigTest {

    private static BuildProperties buildProperties(String version) {
        Properties p = new Properties();
        p.setProperty("version", version);
        return new BuildProperties(p);
    }

    private final OpenAPI api =
            new OpenApiConfig(buildProperties("0.0.1-SNAPSHOT"), "https://app.example.test").customOpenApi();

    @Test
    void carriesTitleAndTheInjectedProjectVersion() {
        assertEquals("EasyCRM API", api.getInfo().getTitle());
        // The version comes from the Gradle project version via BuildProperties, not from a
        // literal and deliberately not from a date: a date would churn the snapshot on every
        // regeneration and make the drift guard in OpenApiSnapshotTest fire on the calendar
        // rather than on a real change.
        assertEquals("0.0.1-SNAPSHOT", api.getInfo().getVersion());
    }

    @Test
    void declaresABearerJwtSecurityScheme() {
        SecurityScheme scheme = api.getComponents().getSecuritySchemes().get("bearer-jwt");
        assertNotNull(scheme, "scheme must be named exactly bearer-jwt; the snapshot depends on it");
        assertEquals(SecurityScheme.Type.HTTP, scheme.getType());
        assertEquals("bearer", scheme.getScheme());
        assertEquals("JWT", scheme.getBearerFormat());
    }

    @Test
    void publishesTheConfiguredPublicBaseUrlAsTheServer() {
        // Not springdoc's request-derived guess: left to itself it stamped the snapshot with
        // "http://localhost", the origin of whatever MockMvc call generated the document.
        assertEquals(1, api.getServers().size());
        assertEquals("https://app.example.test", api.getServers().get(0).getUrl());
    }

    @Test
    void appliesTheSchemeGlobally() {
        // Most routes need a JWT; the handful that do not are the documented exceptions
        // (auth, public share, invitation accept/preview). A global requirement is the
        // smaller, more honest default than annotating 74 endpoints individually.
        assertEquals(1, api.getSecurity().size());
        assertTrue(api.getSecurity().get(0).containsKey("bearer-jwt"));
    }
}
