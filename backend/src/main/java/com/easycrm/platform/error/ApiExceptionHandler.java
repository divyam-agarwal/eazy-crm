package com.easycrm.platform.error;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
        return body(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage(), ex.getFields());
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
        return body(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "request is invalid", fields);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ApiResponse(
            responseCode = "400",
            description = "bean-validation failure on the request body",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<ApiErrorResponse> invalid(MethodArgumentNotValidException ex) {
        Map<String, Object> fields = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> fields.put(fe.getField(), fe.getDefaultMessage()));
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "request is invalid", fields);
    }

    private ResponseEntity<ApiErrorResponse> body(
            HttpStatus status, String code, String message, Map<String, Object> fields) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(new ApiError(code, message, fields)));
    }
}
