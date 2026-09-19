package com.easycrm.platform.openapi;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * openapi-typescript makes every property optional unless the schema lists it under
 * {@code required}. The browser session is built from these responses, so an optional
 * {@code accessToken} would push non-null assertions into every consumer (spec 2026-09-14-f0 §4.4).
 * Reads the COMMITTED snapshot, which OpenApiSnapshotTest holds equal to what springdoc generates.
 */
class OpenApiRequiredFieldsTest {

    // LinkedHashMap, not Map.of: iteration order is fixed, so the FIRST failure is always the
    // AuthResponse message Step 3 predicts (Map.of's order is deliberately randomized per JVM).
    private static final Map<String, List<String>> REQUIRED = new LinkedHashMap<>();

    static {
        REQUIRED.put("AuthResponse", List.of("accessToken", "userId", "tenantId", "tenantSlug", "email", "role"));
        REQUIRED.put("MeResponse", List.of("userId", "tenantId", "tenantSlug", "email", "role"));
        REQUIRED.put("InvitationPreviewResponse", List.of("businessName", "email", "role"));
        REQUIRED.put("SignupStatusResponse", List.of("open"));
        REQUIRED.put("ApiErrorResponse", List.of("error"));
        REQUIRED.put("ApiError", List.of("code", "message"));
    }

    /**
     * Schemas returned by a 2xx response that predate this rule. F0b annotates the six above; every
     * NEW response schema must declare required or be added here deliberately, which is the point —
     * a frozen baseline makes the omission visible in review instead of silent in the client.
     * F1 empties this set for the schemas its screens render (ROADMAP item 4, F1).
     *
     * <p>frozen 2026-09-19: pre-F0b response schemas; F1 annotates the ones its screens render.
     */
    private static final Set<String> LEGACY_UNANNOTATED = Set.of(
            "ActivityResponse",
            "ContactResponse",
            "CustomerResponse",
            "EnquiryResponse",
            "FollowUpResponse",
            "FollowUpSummaryResponse",
            "InvitationResponse",
            "MemberResponse",
            "OrderResponse",
            "PageResponseActivityResponse",
            "PageResponseCustomerResponse",
            "PageResponseEnquiryResponse",
            "PageResponseFollowUpResponse",
            "PageResponseOrderResponse",
            "PageResponsePriceListResponse",
            "PageResponseProductResponse",
            "PageResponseQuotationResponse",
            "PendingInvitationResponse",
            "PriceListItemResponse",
            "PriceListResponse",
            "ProductResponse",
            "QuotationResponse",
            "QuotationVersionResponse",
            "ShareResponse",
            "TenantResponse");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemas() throws Exception {
        String yaml = Files.readString(Path.of(System.getProperty("openapi.snapshot")), StandardCharsets.UTF_8);
        Map<String, Object> doc = new Yaml().load(yaml);
        return (Map<String, Object>) ((Map<String, Object>) doc.get("components")).get("schemas");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sessionAndErrorSchemasDeclareTheirRequiredFields() throws Exception {
        var schemas = schemas();
        for (var entry : REQUIRED.entrySet()) {
            var schema = (Map<String, Object>) schemas.get(entry.getKey());
            assertNotNull(schema, "no schema named " + entry.getKey() + " in the snapshot");
            var required = (List<String>) schema.getOrDefault("required", List.of());
            assertEquals(
                    new TreeSet<>(entry.getValue()),
                    new TreeSet<>(required),
                    entry.getKey() + " must list exactly these as required");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyRequiredNameIsARealProperty() throws Exception {
        // requiredProperties is a list of strings: a typo there would emit a required name that no
        // property has, and the generated client would demand a field the server never sends.
        var schemas = schemas();
        for (var name : REQUIRED.keySet()) {
            var schema = (Map<String, Object>) schemas.get(name);
            var properties = ((Map<String, Object>) schema.get("properties")).keySet();
            var required = (List<String>) schema.getOrDefault("required", List.of());
            assertTrue(properties.containsAll(required), name + " requires " + required + " but has " + properties);
            assertFalse(required.isEmpty(), name + " declares no required fields (non-vacuity)");
        }
    }

    /**
     * The general rule (owner decision, 2026-09-19): a response schema whose fields the browser
     * renders must say which fields it always sends, or openapi-typescript types every one of them
     * as optional and the optionality spreads into every consumer. A NEW schema is caught here;
     * an old one is caught only when someone removes it from LEGACY_UNANNOTATED.
     */
    @Test
    @SuppressWarnings("unchecked")
    void everySchemaReturnedByA2xxDeclaresRequired() throws Exception {
        var schemas = schemas();
        var returned = new TreeSet<String>();
        var paths = (Map<String, Object>) new Yaml()
                .<Map<String, Object>>load(
                        Files.readString(Path.of(System.getProperty("openapi.snapshot")), StandardCharsets.UTF_8))
                .get("paths");
        collect2xxSchemaNames(paths, returned); // walks operations → responses "2xx" → content → $ref
        var missing = new TreeSet<String>();
        for (var name : returned) {
            if (LEGACY_UNANNOTATED.contains(name)) continue;
            var schema = (Map<String, Object>) schemas.get(name);
            if (schema == null || !schema.containsKey("properties")) continue; // enums, primitives
            if (((List<String>) schema.getOrDefault("required", List.of())).isEmpty()) missing.add(name);
        }
        assertEquals(
                Set.of(),
                missing,
                "these schemas are returned by a 2xx response but declare no required fields: " + missing
                        + " — annotate them with @Schema(requiredProperties = …) or add them to"
                        + " LEGACY_UNANNOTATED with a reason");
        assertFalse(returned.isEmpty(), "walked zero 2xx schemas (non-vacuity)");
        assertTrue(returned.contains("AuthResponse"), "the walk must reach AuthResponse (non-vacuity)");
    }

    /**
     * Walks every path → operation → responses whose status key starts with "2" → content → each
     * media type's schema, collecting the last segment of every {@code $ref} it finds. Recurses
     * into {@code items} so an array-of-X response also yields X.
     */
    @SuppressWarnings("unchecked")
    private static void collect2xxSchemaNames(Map<String, Object> paths, Set<String> out) {
        if (paths == null) return;
        for (var pathEntry : paths.values()) {
            if (!(pathEntry instanceof Map<?, ?> pathMap)) continue;
            for (var opEntry : ((Map<String, Object>) pathMap).values()) {
                if (!(opEntry instanceof Map<?, ?> opMap)) continue;
                Object responsesObj = ((Map<String, Object>) opMap).get("responses");
                if (!(responsesObj instanceof Map<?, ?> responses)) continue;
                for (var resEntry : ((Map<String, Object>) responses).entrySet()) {
                    if (!String.valueOf(resEntry.getKey()).startsWith("2")) continue;
                    if (!(resEntry.getValue() instanceof Map<?, ?> response)) continue;
                    Object contentObj = ((Map<String, Object>) response).get("content");
                    if (!(contentObj instanceof Map<?, ?> content)) continue;
                    for (var mediaEntry : ((Map<String, Object>) content).values()) {
                        if (!(mediaEntry instanceof Map<?, ?> mediaType)) continue;
                        Object schema = ((Map<String, Object>) mediaType).get("schema");
                        collectSchemaRef(schema, out);
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectSchemaRef(Object schema, Set<String> out) {
        if (!(schema instanceof Map<?, ?> schemaMap)) return;
        Object ref = ((Map<String, Object>) schemaMap).get("$ref");
        if (ref instanceof String refString) {
            out.add(refString.substring(refString.lastIndexOf('/') + 1));
        }
        Object items = ((Map<String, Object>) schemaMap).get("items");
        if (items != null) {
            collectSchemaRef(items, out);
        }
    }
}
