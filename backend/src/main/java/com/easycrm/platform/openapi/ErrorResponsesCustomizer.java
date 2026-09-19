package com.easycrm.platform.openapi;

import io.swagger.v3.oas.models.Operation;
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
 * Adds a {@code 400} and a {@code 429} response to every operation that does not already declare
 * one.
 *
 * <p>Both are real, application-wide behaviours that {@code @ApiResponse} annotations alone
 * cannot document: {@code RateLimitFilter} answers 429 for any policy-matched route without ever
 * reaching a controller, and bean-validation failures on a {@code @Valid @RequestBody} answer 400
 * through {@code ApiExceptionHandler.invalid(MethodArgumentNotValidException)} — but springdoc
 * does not merge that advice-level {@code @ApiResponse} into an operation's response map, because
 * {@code MethodArgumentNotValidException} is one Spring's own exception-handling contract already
 * claims (verified: moving the same annotation onto {@code AuthController.login} directly makes
 * springdoc emit it, so the advice entry for this exception type is the thing being skipped, not
 * {@code @ApiResponse} itself). A global customizer is the mechanism that works for both: it runs
 * once, after springdoc has already built the operation graph from the controllers, and only adds
 * what an operation does not already declare.
 *
 * <p>Every operation, not just {@code /api/v1/auth/**}: the filter and the validation advice both
 * apply application-wide, so scoping the customizer to one path prefix would just document the gap
 * everywhere else instead of closing it. {@code OpenApiRequiredFieldsTest} pins the auth/invitation
 * slice the frontend actually mocks.
 */
@Configuration
public class ErrorResponsesCustomizer {

    static final String API_ERROR_RESPONSE_REF = "#/components/schemas/ApiErrorResponse";

    private static final String BAD_REQUEST_DESCRIPTION =
            "bean-validation failure on the request body; fields/fieldCodes name the offending inputs";
    private static final String RATE_LIMITED_DESCRIPTION = "rate limit exceeded; Retry-After names the wait in seconds";

    // Named distinctly from the enclosing @Configuration class: springdoc/Spring registers the
    // configuration class itself as a bean named by decapitalizing its simple name
    // ("errorResponsesCustomizer"), which would otherwise collide with a @Bean method of the
    // same name and fail context startup with BeanDefinitionOverrideException.
    @Bean
    public GlobalOpenApiCustomizer errorResponsesOpenApiCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) return;
            openApi.getPaths().values().stream()
                    .flatMap(pathItem -> pathItem.readOperations().stream())
                    .forEach(operation -> {
                        addIfAbsent(operation, "400", BAD_REQUEST_DESCRIPTION, false);
                        addIfAbsent(operation, "429", RATE_LIMITED_DESCRIPTION, true);
                    });
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
