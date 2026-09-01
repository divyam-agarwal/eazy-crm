package com.easycrm.platform.openapi;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.easycrm.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The drift guard: the document springdoc generates from the current controllers must equal the
 * committed snapshot at docs/api/openapi.yaml. Adding an endpoint, renaming a field, changing a
 * status code or adding a query parameter all fail {@code ./gradlew clean check} until the
 * snapshot is regenerated and committed alongside the change — which is the whole difference
 * between having a Swagger page and having a contract.
 *
 * <p>Run {@code ./gradlew updateOpenApiSnapshot} to rewrite the snapshot instead of asserting
 * against it. That is deliberately this same test in a second mode and not a second generator:
 * two generation paths could disagree, and then neither artefact would mean anything.
 *
 * <p>{@code addFilters = false} bypasses the security chain — SecurityConfig ends in
 * {@code denyAll()}, so the springdoc route is not reachable in a test otherwise. Nothing here
 * asserts anything about authorization; ApiDocsExposureTest and the dev-profile chain govern
 * real exposure.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "springdoc.api-docs.enabled=true")
class OpenApiSnapshotTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void generatedDocumentMatchesTheCommittedSnapshot() throws Exception {
        String generated = mvc.perform(get("/v3/api-docs.yaml"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertFalse(generated.isBlank(), "springdoc produced an empty document");
        assertTrue(generated.contains("EasyCRM API"), "the OpenApiConfig info block is missing");

        Path snapshot = Path.of(System.getProperty("openapi.snapshot"));

        if (Boolean.getBoolean("openapi.write")) {
            Files.createDirectories(snapshot.getParent());
            Files.writeString(snapshot, generated, StandardCharsets.UTF_8);
            System.out.println("openapi: wrote snapshot to " + snapshot);
            return;
        }

        assertTrue(Files.exists(snapshot), "missing snapshot: run ./gradlew updateOpenApiSnapshot");

        String committed = Files.readString(snapshot, StandardCharsets.UTF_8);
        if (!committed.equals(generated)) {
            // Dump the actual output so the difference can be diffed rather than guessed at from
            // a multi-thousand-line assertion message.
            Path actual = Path.of("build", "openapi-actual.yaml").toAbsolutePath();
            Files.createDirectories(actual.getParent());
            Files.writeString(actual, generated, StandardCharsets.UTF_8);
            fail("The API changed but docs/api/openapi.yaml did not.\n"
                    + "  Regenerate: ./gradlew updateOpenApiSnapshot\n"
                    + "  Then commit the snapshot with the change that caused it.\n"
                    + "  Generated output: " + actual + "\n"
                    + "  Diff: diff " + snapshot + " " + actual);
        }
    }
}
