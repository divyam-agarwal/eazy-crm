package com.easycrm.platform.error;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConflictExceptionTest {

    @Test
    void messageOnlyConflictCarriesNoFields() {
        ConflictException ex = new ConflictException("already disabled");
        assertEquals("already disabled", ex.getMessage());
        assertNull(ex.getFields(), "a plain conflict must serialize without a fields key");
    }

    @Test
    void structuredConflictExposesItsFields() {
        ConflictException ex = new ConflictException("still holds work", Map.of("customers", 3L));
        assertEquals(3L, ex.getFields().get("customers"));
    }

    @Test
    void fieldsAreDefensivelyCopiedAndUnmodifiable() {
        Map<String, Object> source = new HashMap<>();
        source.put("customers", 1L);
        ConflictException ex = new ConflictException("still holds work", source);

        source.put("enquiries", 9L);
        assertNull(ex.getFields().get("enquiries"), "must not retain the caller's map");
        assertThrows(UnsupportedOperationException.class, () -> ex.getFields().put("orders", 1L));
    }

    @Test
    void fieldsPreserveInsertionOrder() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("customers", 1L);
        source.put("enquiries", 2L);
        source.put("follow-ups", 3L);
        assertEquals(
                "[customers, enquiries, follow-ups]",
                new ConflictException("m", source).getFields().keySet().toString());
    }

    @Test
    void nullFieldsAreTolerated() {
        assertNull(new ConflictException("m", null).getFields());
    }
}
