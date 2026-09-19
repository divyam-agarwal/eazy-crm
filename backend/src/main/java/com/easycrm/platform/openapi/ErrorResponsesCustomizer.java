package com.easycrm.platform.openapi;

import com.easycrm.platform.ratelimit.RateLimitProperties;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Adds a {@code 400} response to every operation that has a request body and does not already
 * declare one, and a {@code 429} response to every operation whose path a configured rate-limit
 * policy actually matches and that does not already declare one.
 *
 * <p>Both are real behaviours that {@code @ApiResponse} annotations alone cannot document:
 * {@code RateLimitFilter} answers 429 for any policy-matched route without ever reaching a
 * controller, and bean-validation failures on a {@code @Valid @RequestBody} answer 400 through
 * {@code ApiExceptionHandler.invalid(MethodArgumentNotValidException)} — but springdoc does not
 * merge that advice-level {@code @ApiResponse} into an operation's response map, because {@code
 * MethodArgumentNotValidException} is one Spring's own exception-handling contract already claims
 * (verified: moving the same annotation onto {@code AuthController.login} directly makes springdoc
 * emit it, so the advice entry for this exception type is the thing being skipped, not {@code
 * @ApiResponse} itself). A global customizer is the mechanism that works for both: it runs once,
 * after springdoc has already built the operation graph from the controllers, and only adds what
 * an operation does not already declare.
 *
 * <p>Both responses are scoped to where they can actually occur — an earlier version added both
 * to every operation unconditionally, which put "bean-validation failure on the request body" on
 * bodyless operations (e.g. a bare {@code DELETE}) and 429 on routes no configured policy ever
 * matches (e.g. {@code /api/v1/customers/**}); a contract asserting a mechanism that cannot happen
 * is worse than one that omits it (review finding B1, F0b Task 1 fix round 1). 400 is gated on
 * {@link Operation#getRequestBody()} being present — {@code ApiExceptionHandler}'s only 400 source
 * is bean validation on a {@code @Valid} body, so a body-less operation cannot produce one. 429 is
 * gated by reusing {@link RateLimitProperties#policyFor(String)} — the same matcher {@code
 * RateLimitFilter} calls at request time — against the OpenAPI path template itself (e.g. {@code
 * /api/v1/customers/{customerId}}). That template is not a literal request path, but it maps
 * cleanly onto {@code policyFor} for every route in this application today: the only two
 * path-variable segments under a rate-limited prefix ({@code /api/v1/auth/invitations/{token}} and
 * {@code /public/q/{token}}) fall under wildcard ({@code **} / {@code *}) policy patterns, which
 * match literal placeholder text exactly as they would match any other segment, so the answer this
 * produces agrees with what {@code RateLimitFilter} would do for a real request on that route. Had
 * that not held — a policy keyed on a specific alternation over a path-variable segment, say — the
 * fallback specified for this case was a hardcoded check against the two prefixes {@code
 * RateLimitProperties} configures policies under ({@code /public/q/}, {@code /api/v1/auth/}); it
 * was not needed.
 */
@Configuration
public class ErrorResponsesCustomizer {

    static final String API_ERROR_RESPONSE_REF = "#/components/schemas/ApiErrorResponse";

    private static final String BAD_REQUEST_DESCRIPTION =
            "bean-validation failure on the request body; fields/fieldCodes name the offending inputs";
    private static final String RATE_LIMITED_DESCRIPTION = "rate limit exceeded; Retry-After names the wait in seconds";

    private final RateLimitProperties rateLimitProperties;

    public ErrorResponsesCustomizer(RateLimitProperties rateLimitProperties) {
        this.rateLimitProperties = rateLimitProperties;
    }

    // Named distinctly from the enclosing @Configuration class: springdoc/Spring registers the
    // configuration class itself as a bean named by decapitalizing its simple name
    // ("errorResponsesCustomizer"), which would otherwise collide with a @Bean method of the
    // same name and fail context startup with BeanDefinitionOverrideException.
    @Bean
    public GlobalOpenApiCustomizer errorResponsesOpenApiCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) return;
            for (var pathEntry : openApi.getPaths().entrySet()) {
                String pathTemplate = pathEntry.getKey();
                PathItem pathItem = pathEntry.getValue();
                // policyFor() ignores RateLimitProperties.enabled() -- it only walks the
                // configured policies list -- so this stays correct even though the snapshot
                // is generated under a test profile that sets easycrm.rate-limit.enabled=false
                // (IntegrationTest); the committed contract must describe the mechanism as it
                // exists in production, not as toggled off for a test run.
                boolean rateLimited =
                        rateLimitProperties.policyFor(pathTemplate).isPresent();
                for (Operation operation : pathItem.readOperations()) {
                    if (operation.getRequestBody() != null) {
                        addIfAbsent(operation, "400", BAD_REQUEST_DESCRIPTION, false);
                    }
                    if (rateLimited) {
                        addIfAbsent(operation, "429", RATE_LIMITED_DESCRIPTION, true);
                    }
                }
            }
        };
    }

    private void addIfAbsent(Operation operation, String statusCode, String description, boolean withRetryAfter) {
        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            responses = new ApiResponses();
            operation.setResponses(responses);
        }
        if (responses.containsKey(statusCode)) return;

        ApiResponse response = new ApiResponse()
                .description(description)
                .content(new Content()
                        .addMediaType(
                                "application/json",
                                new MediaType().schema(new Schema<>().$ref(API_ERROR_RESPONSE_REF))));
        if (withRetryAfter) {
            response.addHeaderObject(
                    "Retry-After",
                    new Header().description("seconds to wait before retrying").schema(new StringSchema()));
        }
        responses.addApiResponse(statusCode, response);
    }
}
