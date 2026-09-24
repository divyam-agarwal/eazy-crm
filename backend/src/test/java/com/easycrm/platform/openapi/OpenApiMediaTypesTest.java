package com.easycrm.platform.openapi;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.yaml.snakeyaml.Yaml;

/**
 * openapi-typescript keys response types by media type, so a response keyed '*' + '/' + '*'
 * gives the generated frontend client an untyped body. This reads the COMMITTED snapshot, which
 * OpenApiSnapshotTest already holds equal to what springdoc generates.
 *
 * <p>Non-vacuity: the guard also asserts JSON responses exist in quantity and that both PDF routes
 * are keyed application/pdf — a document that parsed to nothing, or a walker that visited nothing,
 * cannot pass.
 */
class OpenApiMediaTypesTest {

    private static final String ANY = "*/*";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> paths() throws Exception {
        String yaml = Files.readString(Path.of(System.getProperty("openapi.snapshot")), StandardCharsets.UTF_8);
        Map<String, Object> doc = new Yaml().load(yaml);
        return (Map<String, Object>) doc.get("paths");
    }

    /** Every (path, method, status, mediaType) the document declares. */
    @SuppressWarnings("unchecked")
    private static List<String[]> responseMediaTypes() throws Exception {
        List<String[]> out = new ArrayList<>();
        for (var path : paths().entrySet()) {
            for (var op : ((Map<String, Object>) path.getValue()).entrySet()) {
                if (!(op.getValue() instanceof Map<?, ?> opMap)) continue;
                Object responses = opMap.get("responses");
                if (!(responses instanceof Map<?, ?> resMap)) continue;
                for (var res : resMap.entrySet()) {
                    Object content = ((Map<String, Object>) res.getValue()).get("content");
                    if (!(content instanceof Map<?, ?> contentMap)) continue;
                    for (Object mediaType : contentMap.keySet()) {
                        out.add(new String[] {
                            path.getKey(), op.getKey(), String.valueOf(res.getKey()), (String) mediaType
                        });
                    }
                }
            }
        }
        return out;
    }

    @Test
    void noResponseIsKeyedAnyMediaType() throws Exception {
        List<String> offenders = responseMediaTypes().stream()
                .filter(r -> ANY.equals(r[3]))
                .map(r -> r[1].toUpperCase() + " " + r[0] + " " + r[2])
                .toList();
        assertTrue(
                offenders.isEmpty(),
                offenders.size() + " responses keyed */*, e.g. "
                        + offenders.stream().limit(5).toList());
    }

    @Test
    void jsonResponsesExistInQuantity() throws Exception {
        long json = responseMediaTypes().stream()
                .filter(r -> "application/json".equals(r[3]))
                .count();
        assertTrue(json >= 400, "only " + json + " application/json responses; the walker or document is broken");
    }

    /**
     * ApiExceptionHandler.invalid carries {@code @ApiResponse(responseCode = "400", ...)}, but
     * springdoc does not merge that advice-level annotation into an operation's response map for
     * {@code MethodArgumentNotValidException} (Spring's own exception-handling contract for that
     * type wins) — so 400 was documented nowhere, and 429 (RateLimitFilter, which answers before
     * any controller or advice runs) was documented nowhere at all. Both matter to the frontend:
     * {@code applyApiError} has a 400 branch (bean-validation → fieldCodes) and a 429 branch
     * (Retry-After), and openapi-msw can only type a response the contract documents. Fixed by
     * ErrorResponsesCustomizer, a global OpenApiCustomizer bean.
     *
     * <p>429 is asserted for every operation under /api/v1/auth/** (all eight sit under the "auth" or
     * "session" rate-limit policy, both prefixed /api/v1/auth/); 400 only for the three that actually
     * take a {@code @Valid} request body (signup, login, invitation accept) — GET /me, GET
     * signup/status, POST refresh, POST logout and GET the invitation preview take no body and cannot
     * produce a bean-validation 400 (review finding B1, F0b Task 1 fix round 1;
     * {@link #everyOperationDocuments400IffItCanProduceOne()} is the general-purpose scoping check).
     */
    @Test
    @SuppressWarnings("unchecked")
    void authOperationsDocument429AndBodyBearingOnesDocument400() throws Exception {
        var paths = paths();
        var authOperations = paths.entrySet().stream()
                .filter(e -> e.getKey().toString().startsWith("/api/v1/auth"))
                .toList();
        assertFalse(authOperations.isEmpty(), "walked zero /api/v1/auth/** paths (non-vacuity)");

        var bodyBearingRoutes = Set.of(
                "/api/v1/auth/signup post", "/api/v1/auth/login post", "/api/v1/auth/invitations/{token}/accept post");
        var seenBodyBearing = new TreeSet<String>();

        for (var pathEntry : authOperations) {
            for (var opEntry : ((Map<String, Object>) pathEntry.getValue()).entrySet()) {
                if (!(opEntry.getValue() instanceof Map<?, ?> opMap)) continue;
                String route = pathEntry.getKey() + " " + opEntry.getKey();
                boolean hasBody = opMap.containsKey("requestBody");
                if (hasBody) seenBodyBearing.add(route);
                var responses = (Map<String, Object>) opMap.get("responses");
                assertNotNull(responses, route + " has no responses");

                var badRequest = (Map<String, Object>) responses.get("400");
                if (hasBody) {
                    assertNotNull(badRequest, route + " has a request body but does not document 400");
                    assertEquals(
                            "#/components/schemas/ApiErrorResponse",
                            ((Map<String, Object>)
                                            ((Map<String, Object>) ((Map<String, Object>) badRequest.get("content"))
                                                            .get("application/json"))
                                                    .get("schema"))
                                    .get("$ref"),
                            route + " 400 schema");
                } else {
                    assertNull(badRequest, route + " has no request body; must not document 400");
                }

                var tooManyRequests = (Map<String, Object>) responses.get("429");
                assertNotNull(tooManyRequests, route + " does not document 429");
                assertEquals(
                        "#/components/schemas/ApiErrorResponse",
                        ((Map<String, Object>)
                                        ((Map<String, Object>) ((Map<String, Object>) tooManyRequests.get("content"))
                                                        .get("application/json"))
                                                .get("schema"))
                                .get("$ref"),
                        route + " 429 schema");
                var headers = (Map<String, Object>) tooManyRequests.get("headers");
                assertNotNull(headers, route + " 429 does not document the Retry-After header");
                assertTrue(headers.containsKey("Retry-After"), route + " 429 headers " + headers.keySet());
            }
        }
        assertEquals(
                new TreeSet<>(bodyBearingRoutes),
                seenBodyBearing,
                "expected exactly these /api/v1/auth/** operations to carry a request body");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> post(String route) throws Exception {
        Map<String, Object> path = (Map<String, Object>) paths().get(route);
        assertNotNull(path, route + " missing from the document");
        return (Map<String, Object>) path.get("post");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> get(String route) throws Exception {
        Map<String, Object> path = (Map<String, Object>) paths().get(route);
        assertNotNull(path, route + " missing from the document");
        return (Map<String, Object>) path.get("get");
    }

    /** Every (path, method, operation-map) the document declares. */
    @SuppressWarnings("unchecked")
    private static List<Object[]> allOperations() throws Exception {
        List<Object[]> out = new ArrayList<>();
        for (var pathEntry : paths().entrySet()) {
            for (var opEntry : ((Map<String, Object>) pathEntry.getValue()).entrySet()) {
                if (!(opEntry.getValue() instanceof Map<?, ?> opMap)) continue;
                out.add(new Object[] {pathEntry.getKey(), opEntry.getKey(), opMap});
            }
        }
        return out;
    }

    /**
     * Mirrors {@code ErrorResponsesCustomizer.hasConstrainedParameter}/{@code isConstrained} exactly:
     * a parameter is "constrained" iff its schema carries {@code maxLength}, {@code minLength} or
     * {@code pattern} — the same three keys the producer checks. If this definition ever drifts from
     * the customizer's, that drift is itself the bug this test exists to surface, not something to
     * paper over here.
     */
    @SuppressWarnings("unchecked")
    private static boolean hasConstrainedParameter(Map<String, Object> op) {
        Object params = op.get("parameters");
        if (!(params instanceof List<?> list)) return false;
        for (Object p : list) {
            if (!(p instanceof Map<?, ?> param)) continue;
            Object schema = param.get("schema");
            if (!(schema instanceof Map<?, ?> schemaMap)) continue;
            if (schemaMap.get("maxLength") != null
                    || schemaMap.get("minLength") != null
                    || schemaMap.get("pattern") != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Operations grandfathered out of {@link #everyOperationDocuments400IffItCanProduceOne()}.
     * Empty: as of this test's introduction (F1a Task 4 fix round 1, R17), the universal walk found
     * zero pre-existing divergences between {@code ErrorResponsesCustomizer}'s own gating condition
     * and what the committed snapshot documents. Any future entry here must name the operation and
     * the reason, in the style {@code OpenApiRequiredFieldsTest.LEGACY_UNANNOTATED} already uses for
     * this file's sibling baseline.
     */
    private static final Set<String> LEGACY_400_SCOPING_EXEMPT = Set.of();

    /**
     * The negative half of {@link #authOperationsDocument429AndBodyBearingOnesDocument400}:
     * {@code ErrorResponsesCustomizer} must NOT add 400 where it cannot occur (review finding B1, F0b
     * Task 1 fix round 1), and MUST add it everywhere it can — checked as one invariant across every
     * operation in the document, not sampled on a few hand-picked routes.
     *
     * <p>R17 (F1a Task 4 fix round 1): the previous version of this test named three concrete witness
     * routes — one asserted to have neither a body nor a constrained parameter, the others asserted to
     * have one. That is a maintenance trap with a finite supply: F1a Task 3 had already moved the
     * "unconstrained" witness from {@code GET /api/v1/customers} to {@code GET /api/v1/products}
     * (Task 3 gave customers a {@code q} parameter), and F1a Task 4 immediately invalidated the new
     * witness too (Task 4 gave products its own {@code q}), forcing a second repoint to
     * {@code GET /api/v1/orders} — with nothing in either breaking diff pointing back at this file.
     * Every future task that legitimately adds a constrained parameter to whatever route currently
     * serves as the witness breaks this test for a reason unrelated to what it changed.
     *
     * <p>The fix: derive the witness from the property instead of naming one. {@code
     * ErrorResponsesCustomizer} documents 400 on an operation iff {@code operation.getRequestBody() !=
     * null || hasConstrainedParameter(operation)} — this test recomputes that exact condition from the
     * committed snapshot for every operation and asserts the document agrees, for all of them at once.
     * No future parameter addition anywhere in the API can invalidate this test by changing what it
     * happens to be sampling; the only way to break it is to make the snapshot and the customizer
     * actually disagree, which is precisely the bug class this test exists to catch.
     */
    @Test
    void everyOperationDocuments400IffItCanProduceOne() throws Exception {
        List<Object[]> operations = allOperations();
        assertFalse(operations.isEmpty(), "walked zero operations (non-vacuity)");

        List<String> violations = new ArrayList<>();
        boolean sawOperationExpecting400 = false;
        boolean sawOperationExpectingNo400 = false;

        for (Object[] entry : operations) {
            String path = (String) entry[0];
            String method = (String) entry[1];
            @SuppressWarnings("unchecked")
            Map<String, Object> op = (Map<String, Object>) entry[2];
            String route = method.toUpperCase() + " " + path;
            if (LEGACY_400_SCOPING_EXEMPT.contains(route)) continue;

            boolean hasBody = op.containsKey("requestBody");
            boolean constrained = hasConstrainedParameter(op);
            boolean expected400 = hasBody || constrained;
            if (expected400) sawOperationExpecting400 = true;
            else sawOperationExpectingNo400 = true;

            @SuppressWarnings("unchecked")
            var responses = (Map<String, Object>) op.get("responses");
            boolean documents400 = responses != null && responses.containsKey("400");

            if (documents400 != expected400) {
                violations.add(route + ": documents400=" + documents400 + " but hasRequestBody=" + hasBody
                        + " hasConstrainedParameter=" + constrained);
            }
        }

        assertTrue(
                sawOperationExpecting400, "no operation with a body or a constrained parameter was seen (non-vacuity)");
        assertTrue(sawOperationExpectingNo400, "no bodyless, unconstrained operation was seen (non-vacuity)");
        assertTrue(
                violations.isEmpty(),
                violations.size() + " operations violate documents400 == (hasRequestBody || hasConstrainedParameter): "
                        + violations);
    }

    /**
     * The path patterns {@code RateLimitProperties} binds from {@code easycrm.rate-limit.policies}
     * in production — read from the same {@code application.yml} Spring Boot loads (on the test
     * classpath, since {@code src/main/resources} is part of the shared main output), not
     * hand-copied here. A hand-copied list would silently stop matching this test's intent the
     * day a policy's {@code path} changed in configuration without anyone touching this file.
     */
    @SuppressWarnings("unchecked")
    private static List<PathPattern> rateLimitPolicyPatterns() throws Exception {
        try (var in = OpenApiMediaTypesTest.class.getClassLoader().getResourceAsStream("application.yml")) {
            assertNotNull(in, "application.yml not found on the test classpath");
            Map<String, Object> doc = new Yaml().load(in);
            Map<String, Object> easycrm = (Map<String, Object>) doc.get("easycrm");
            Map<String, Object> rateLimit = (Map<String, Object>) easycrm.get("rate-limit");
            List<Map<String, Object>> policies = (List<Map<String, Object>>) rateLimit.get("policies");
            return policies.stream()
                    .map(p -> (String) p.get("path"))
                    .map(PathPatternParser.defaultInstance::parse)
                    .toList();
        }
    }

    private static boolean anyPolicyMatches(List<PathPattern> patterns, String pathTemplate) {
        return patterns.stream().anyMatch(p -> p.matches(PathContainer.parsePath(pathTemplate)));
    }

    /**
     * The negative half of rate-limit coverage. A previous test (replaced by {@link
     * #everyOperationDocuments400IffItCanProduceOne()}'s sibling for 400) asserted 429 only on
     * {@code /api/v1/auth/**} and never checked that routes NO policy matches stay undocumented —
     * so a regression making {@code ErrorResponsesCustomizer} add 429 to every operation
     * unconditionally would have shipped silently. This recomputes {@code documents429 == (a
     * rate-limit policy matches this path)} for every operation in the document, the same way
     * {@link #everyOperationDocuments400IffItCanProduceOne()} recomputes the 400 half — using the
     * exact matcher ({@code PathPattern}) and the exact policy paths ({@code
     * RateLimitProperties.policyFor}'s source configuration) {@code ErrorResponsesCustomizer}
     * itself uses, rather than a hand-picked witness route.
     */
    @Test
    void everyOperationDocuments429IffARateLimitPolicyMatches() throws Exception {
        List<PathPattern> patterns = rateLimitPolicyPatterns();
        assertFalse(patterns.isEmpty(), "no rate-limit policies found in application.yml (non-vacuity)");

        List<Object[]> operations = allOperations();
        assertFalse(operations.isEmpty(), "walked zero operations (non-vacuity)");

        List<String> violations = new ArrayList<>();
        boolean sawOperationExpecting429 = false;
        boolean sawOperationExpectingNo429 = false;

        for (Object[] entry : operations) {
            String path = (String) entry[0];
            String method = (String) entry[1];
            @SuppressWarnings("unchecked")
            Map<String, Object> op = (Map<String, Object>) entry[2];
            String route = method.toUpperCase() + " " + path;

            boolean expected429 = anyPolicyMatches(patterns, path);
            if (expected429) sawOperationExpecting429 = true;
            else sawOperationExpectingNo429 = true;

            @SuppressWarnings("unchecked")
            var responses = (Map<String, Object>) op.get("responses");
            boolean documents429 = responses != null && responses.containsKey("429");

            if (documents429 != expected429) {
                violations.add(route + ": documents429=" + documents429 + " but rateLimitPolicyMatches=" + expected429);
            }
        }

        assertTrue(sawOperationExpecting429, "no operation matched by a rate-limit policy was seen (non-vacuity)");
        assertTrue(
                sawOperationExpectingNo429,
                "no operation left unmatched by every rate-limit policy was seen (non-vacuity)");
        assertTrue(
                violations.isEmpty(),
                violations.size() + " operations violate documents429 == (a rate-limit policy matches this path): "
                        + violations);
    }

    @Test
    void logoutDeclares204AndNot200() throws Exception {
        Map<?, ?> responses = (Map<?, ?>) post("/api/v1/auth/logout").get("responses");
        assertTrue(responses.containsKey("204"), "logout responses " + responses.keySet());
        assertFalse(responses.containsKey("200"), "logout responses " + responses.keySet());
    }

    @Test
    void sessionIssuingCreatesDeclare201AndNot200() throws Exception {
        for (String route : List.of("/api/v1/auth/signup", "/api/v1/auth/invitations/{token}/accept")) {
            Map<?, ?> responses = (Map<?, ?>) post(route).get("responses");
            assertTrue(responses.containsKey("201"), route + " responses " + responses.keySet());
            assertFalse(responses.containsKey("200"), route + " responses " + responses.keySet());
        }
    }

    @Test
    void cookieRoutesRequireTheClientHeader() throws Exception {
        for (String route : List.of("/api/v1/auth/refresh", "/api/v1/auth/logout")) {
            Object params = post(route).get("parameters");
            List<?> header = params instanceof List<?> l
                    ? l.stream()
                            .map(p -> (Map<?, ?>) p)
                            .filter(p -> "header".equals(p.get("in")) && "X-EasyCRM-Client".equals(p.get("name")))
                            .filter(p -> Boolean.TRUE.equals(p.get("required")))
                            .toList()
                    : List.of();
            assertEquals(1, header.size(), route + " parameters " + params);
        }
    }

    @Test
    void bothPdfRoutesDeclareApplicationPdfOnSuccess() throws Exception {
        for (String route : List.of("/api/v1/quotations/{id}/pdf", "/public/q/{token}")) {
            List<String> ok = responseMediaTypes().stream()
                    .filter(r -> r[0].equals(route) && r[1].equals("get") && r[2].equals("200"))
                    .map(r -> r[3])
                    .toList();
            assertEquals(List.of("application/pdf"), ok, route + " 200 response media types");
        }
    }
}
