package com.easycrm.platform.error;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import tools.jackson.databind.ObjectMapper;

/**
 * The error envelope is the first thing any client has to handle, and the typed-record
 * conversion this slice makes is a representation change that must not reach the wire.
 * These assertions are deliberately written against the serialized bytes rather than against
 * the Java type, so they hold identically before and after that conversion.
 *
 * <p>If one of these fails after the conversion, the conversion changed behaviour. Fix the
 * code, never the expectation.
 */
class ApiErrorWireFormatTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    // tools.jackson.databind, not com.fasterxml.jackson.databind: Spring Boot 4.1 writes actual
    // HTTP responses with Jackson 3, and com.fasterxml.jackson.databind is only a transitive
    // dependency here -- a test built against it would pin a mapper the app never uses at runtime.
    private final ObjectMapper mapper = new ObjectMapper();

    private String json(ResponseEntity<?> resp) throws Exception {
        return mapper.writeValueAsString(resp.getBody());
    }

    @Test
    void errorWithoutFieldsOmitsTheFieldsKeyEntirely() throws Exception {
        // The case a naive record conversion silently breaks: a record component serializes
        // as "fields":null by default, which is a different document from one with no fields
        // key at all. @JsonInclude(NON_NULL) is what preserves this.
        String body = json(handler.notFound(new NotFoundException("quotation not found")));

        assertEquals("{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"quotation not found\"}}", body);
        assertFalse(body.contains("fields"), "absent fields must not serialize as null");
    }

    @Test
    void errorWithFieldsCarriesThemNested() throws Exception {
        String body = json(handler.validation(new ValidationException("gstin", "GSTIN checksum is invalid")));

        assertEquals(
                "{\"error\":{\"code\":\"VALIDATION_FAILED\",\"message\":\"request is invalid\","
                        + "\"fields\":{\"gstin\":\"GSTIN checksum is invalid\"}}}",
                body);
    }

    @Test
    void everyHandlerProducesTheSameEnvelopeShape() throws Exception {
        // All the direct-call paths, so none of them can drift alone.
        assertTrue(json(handler.unauthorized(new UnauthorizedException("bad credentials")))
                .startsWith("{\"error\":{\"code\":\"UNAUTHORIZED\""));
        assertTrue(json(handler.forbidden(new ForbiddenException("owner only")))
                .startsWith("{\"error\":{\"code\":\"FORBIDDEN\""));
        assertTrue(json(handler.conflict(new ConflictException("slug taken")))
                .startsWith("{\"error\":{\"code\":\"CONFLICT\""));
        assertTrue(json(handler.dataIntegrity(new DataIntegrityViolationException("dup")))
                .startsWith("{\"error\":{\"code\":\"CONFLICT\""));
        assertTrue(json(handler.optimisticLock(
                        new ObjectOptimisticLockingFailureException(Object.class, UUID.randomUUID())))
                .startsWith("{\"error\":{\"code\":\"CONFLICT\""));
    }
}
