package com.easycrm.platform.error;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void validationExceptionMapsTo422WithFields() {
        ResponseEntity<ApiErrorResponse> resp =
                handler.validation(new ValidationException("gstin", "GSTIN checksum is invalid"));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode());
        ApiError error = resp.getBody().error();
        assertEquals("VALIDATION_FAILED", error.code());
        assertEquals("GSTIN checksum is invalid", error.fields().get("gstin"));
    }

    @Test
    void optimisticLockMapsTo409() {
        ResponseEntity<ApiErrorResponse> resp =
                handler.optimisticLock(new ObjectOptimisticLockingFailureException(Object.class, UUID.randomUUID()));

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertEquals("CONFLICT", resp.getBody().error().code());
    }
}
