package com.easycrm.platform.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import java.util.UUID;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    @SuppressWarnings("unchecked")
    void validationExceptionMapsTo422WithFields() {
        ResponseEntity<Map<String, Object>> resp =
            handler.validation(new ValidationException("gstin", "GSTIN checksum is invalid"));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode());
        Map<String, Object> error = (Map<String, Object>) resp.getBody().get("error");
        assertEquals("VALIDATION_FAILED", error.get("code"));
        Map<String, Object> fields = (Map<String, Object>) error.get("fields");
        assertEquals("GSTIN checksum is invalid", fields.get("gstin"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void optimisticLockMapsTo409() {
        ResponseEntity<Map<String, Object>> resp =
            handler.optimisticLock(new ObjectOptimisticLockingFailureException(Object.class, UUID.randomUUID()));

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        Map<String, Object> error = (Map<String, Object>) resp.getBody().get("error");
        assertEquals("CONFLICT", error.get("code"));
    }
}
