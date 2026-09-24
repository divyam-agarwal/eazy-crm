package com.easycrm.platform.error;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.HashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    @ApiResponse(
            responseCode = "404",
            description = "the resource does not exist, or belongs to another tenant",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<ApiErrorResponse> notFound(NotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), null);
    }

    @ExceptionHandler(UnauthorizedException.class)
    @ApiResponse(
            responseCode = "401",
            description = "missing, expired or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<ApiErrorResponse> unauthorized(UnauthorizedException ex) {
        return body(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", ex.getMessage(), null);
    }

    @ExceptionHandler(ForbiddenException.class)
    @ApiResponse(
            responseCode = "403",
            description = "authenticated, but the role does not permit this operation",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<ApiErrorResponse> forbidden(ForbiddenException ex) {
        return body(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage(), null);
    }

    @ExceptionHandler(ConflictException.class)
    @ApiResponse(
            responseCode = "409",
            description = "the request conflicts with existing data",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<ApiErrorResponse> conflict(ConflictException ex) {
        // body() omits the key entirely when fields is null, so every existing 409 in the
        // codebase stays byte-identical — only a conflict that opts in gains a fields object.
        return body(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage(), ex.getFields(), ex.getFieldCodes());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> dataIntegrity(DataIntegrityViolationException ex) {
        // Backstop for unique/constraint violations that slip past app-level pre-checks
        // (update() paths, concurrent create() races). Data stays correct; the client gets 409.
        return body(HttpStatus.CONFLICT, "CONFLICT", "the request conflicts with existing data", null);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> optimisticLock(OptimisticLockingFailureException ex) {
        // A concurrent @Version write lost the race. Data integrity is intact (exactly one writer
        // wins); the loser gets 409 instead of a raw 500. Sibling of the DataIntegrityViolation
        // backstop above — ObjectOptimisticLockingFailureException does NOT extend
        // DataIntegrityViolationException, so it needs its own handler.
        return body(
                HttpStatus.CONFLICT,
                "CONFLICT",
                "the request could not be completed due to a concurrent update; please retry",
                null);
    }

    @ExceptionHandler(ValidationException.class)
    @ApiResponse(
            responseCode = "422",
            description = "semantically invalid request; `fields` names the offending inputs",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<ApiErrorResponse> validation(ValidationException ex) {
        Map<String, Object> fields = new HashMap<>(ex.getFields());
        return body(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "request is invalid", fields, ex.getCodes());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ApiResponse(
            responseCode = "400",
            description = "bean-validation failure on the request body",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<ApiErrorResponse> invalid(MethodArgumentNotValidException ex) {
        Map<String, Object> fields = new HashMap<>();
        Map<String, String> codes = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> {
            fields.put(fe.getField(), fe.getDefaultMessage());
            codes.put(fe.getField(), constraintCode(fe.getCode()));
        });
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "request is invalid", fields, codes);
    }

    /**
     * A {@code @Size}/{@code @Pattern}/etc. on a {@code @RequestParam} or {@code @PathVariable}
     * (as opposed to a {@code @Valid @RequestBody} field) is validated by the AOP method-validation
     * interceptor {@code @Validated} enables on the class, which throws {@link
     * ConstraintViolationException} directly -- Spring's MVC exception handling does not convert
     * this to a 4xx on its own, so without this handler it falls through to a default {@code
     * ProblemDetail} body instead of this API's envelope.
     *
     * <p>Deliberately no {@code @ApiResponse} here: unlike {@code MethodArgumentNotValidException},
     * springdoc does NOT skip merging this exception type's advice-level {@code @ApiResponse} into
     * every operation's response map -- verified empirically (see {@code ErrorResponsesCustomizer}),
     * which would put a bogus 400 on every operation, including ones with no validated parameter at
     * all. {@code ErrorResponsesCustomizer} documents this 400 already, scoped to operations that
     * actually have one.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> invalidParameter(ConstraintViolationException ex) {
        Map<String, Object> fields = new HashMap<>();
        Map<String, String> codes = new HashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String field = lastNode(violation.getPropertyPath());
            fields.put(field, violation.getMessage());
            codes.put(
                    field,
                    constraintCode(violation
                            .getConstraintDescriptor()
                            .getAnnotation()
                            .annotationType()
                            .getSimpleName()));
        }
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "request is invalid", fields, codes);
    }

    /** The property path is "methodName.paramName" (e.g. "list.q"); only the parameter name is client-facing. */
    private static String lastNode(Path path) {
        String last = null;
        for (Path.Node node : path) {
            last = node.getName();
        }
        return last;
    }

    /** "NotBlank" -> "NOT_BLANK". Bean Validation's constraint name is already stable per annotation. */
    static String constraintCode(String constraint) {
        if (constraint == null) return "INVALID";
        return constraint.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT);
    }

    private ResponseEntity<ApiErrorResponse> body(
            HttpStatus status, String code, String message, Map<String, Object> fields) {
        return body(status, code, message, fields, null);
    }

    private ResponseEntity<ApiErrorResponse> body(
            HttpStatus status,
            String code,
            String message,
            Map<String, Object> fields,
            Map<String, String> fieldCodes) {
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(new ApiError(code, message, fields, fieldCodes)));
    }
}
