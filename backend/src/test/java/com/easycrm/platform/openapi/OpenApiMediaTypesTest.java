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
     * {@link #errorResponsesAreScopedToWhereTheyCanOccur()} is the general-purpose negative check).
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

    /**
     * The negative half of {@link #authOperationsDocument400And429WithRetryAfter}: ErrorResponsesCustomizer
     * must NOT add 400 or 429 where they cannot occur (review finding B1, F0b Task 1 fix round 1). Without
     * this test, CI cannot see that class of error at all -- the positive test above only inspects
     * /api/v1/auth/**, where both a request body and a rate-limit policy happen to be true for every
     * operation, so a customizer that added both unconditionally to every operation in the document would
     * still pass it.
     *
     * <p>{@code GET /api/v1/customers} is both body-less (no request payload on a list GET) and outside
     * every configured rate-limit policy (RateLimitProperties covers only /public/q/* and
     * /api/v1/auth/**), and will stay that way structurally -- a list endpoint does not grow a body, and
     * the customers module is not becoming a rate-limited auth/public route. {@code POST
     * /api/v1/customers} isolates the two conditions from each other: it has a body (so 400 is expected)
     * but is not rate-limited (so 429 must still be absent), proving the customizer's two gates are
     * independent rather than one masking the other.
     */
    @Test
    void errorResponsesAreScopedToWhereTheyCanOccur() throws Exception {
        Map<?, ?> listResponses = (Map<?, ?>) get("/api/v1/customers").get("responses");
        assertFalse(listResponses.containsKey("400"), "GET /api/v1/customers has no body; must not document 400");
        assertFalse(
                listResponses.containsKey("429"),
                "GET /api/v1/customers is not under a rate-limit policy; must not document 429");

        Map<?, ?> createResponses = (Map<?, ?>) post("/api/v1/customers").get("responses");
        assertTrue(createResponses.containsKey("400"), "POST /api/v1/customers has a @Valid body; must document 400");
        assertFalse(
                createResponses.containsKey("429"),
                "POST /api/v1/customers is not under a rate-limit policy; must not document 429");
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
