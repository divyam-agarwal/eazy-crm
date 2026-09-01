package com.easycrm.platform.openapi;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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

    /**
     * Comfortably below the 54 paths the app publishes today and nowhere near zero. The point is
     * not to pin the exact number — that is the snapshot's job, and a threshold that had to move
     * on every new endpoint would just get bumped without being read. The point is to catch a
     * document that scanned almost nothing.
     */
    private static final int MINIMUM_API_PATHS = 45;

    @Test
    void generatedDocumentMatchesTheCommittedSnapshot() throws Exception {
        String generated = mvc.perform(get("/v3/api-docs.yaml"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertFalse(generated.isBlank(), "springdoc produced an empty document");
        assertTrue(generated.contains("EasyCRM API"), "the OpenApiConfig info block is missing");

        // Floor on the paths, checked BEFORE the write/read branch so both modes get it. Without
        // it, a misconfigured springdoc.paths-to-match, a stray @Hidden or a narrowed scan
        // base-package would leave `info` intact while `paths` came back empty: write mode would
        // cheerfully overwrite the committed contract with a gutted document and pass, and read
        // mode would pass too, because it then compares the gutted generator output against the
        // now-gutted file. Every other guard here compares the document to itself; this one
        // compares it to a fact about the application.
        long paths = generated.lines().filter(l -> l.startsWith("  /api/v1/")).count();
        assertTrue(
                paths >= MINIMUM_API_PATHS,
                "only " + paths + " /api/v1 paths generated (expected at least " + MINIMUM_API_PATHS
                        + "); component scanning or springdoc path matching is broken");

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

    /**
     * The contract must say money is a string, because the server sends a string:
     * {@code BigDecimalStringModule} serializes every {@code BigDecimal} with
     * {@code writeString}, and {@code QuotationControllerTest} pins that with
     * {@code jsonPath("$.currentVersion.subTotal").value("200.00")}.
     *
     * <p>This is the one assertion in this class that compares the document against something
     * other than itself. The snapshot guard above is a *drift* guard: it is perfectly happy for
     * the contract to be consistently, deterministically, reproducibly wrong, because both sides
     * of its comparison come from the same generator. Nothing else here would notice if the
     * {@code SpringDocUtils.replaceWithSchema} registration in {@code OpenApiConfig} were
     * deleted — the snapshot would simply be regenerated with {@code type: number} throughout and
     * every test would stay green while the published contract stopped describing the server.
     */
    @Test
    void moneyFieldsAreDocumentedAsStrings() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                // A response field...
                .andExpect(jsonPath("$.components.schemas.QuotationVersionResponse.properties.grandTotal.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.OrderResponse.properties.grandTotal.type")
                        .value("string"))
                // ...and a request field: string on the way in is intended, not collateral damage.
                .andExpect(jsonPath("$.components.schemas.ItemRequest.properties.rate.type")
                        .value("string"))
                .andExpect(jsonPath("$.components.schemas.ItemRequest.properties.qty.type")
                        .value("string"));
    }
}
