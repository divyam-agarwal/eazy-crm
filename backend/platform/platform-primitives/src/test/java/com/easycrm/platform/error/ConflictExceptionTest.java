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

    @Test
    void aConflictCanCarryFieldCodesAlongsideFields() {
        ConflictException ex = new ConflictException(
                "slug already taken", Map.of("slug", "slug already taken"), Map.of("slug", "SLUG_TAKEN"));
        assertEquals("SLUG_TAKEN", ex.getFieldCodes().get("slug"));
    }

    @Test
    void fieldCodesAreDefensivelyCopiedAndUnmodifiable() {
        Map<String, String> source = new HashMap<>();
        source.put("slug", "SLUG_TAKEN");
        ConflictException ex = new ConflictException("m", Map.of("slug", "x"), source);

        source.put("other", "OTHER");
        assertNull(ex.getFieldCodes().get("other"), "must not retain the caller's map");
        assertThrows(
                UnsupportedOperationException.class, () -> ex.getFieldCodes().put("x", "Y"));
    }

    @Test
    void fieldCodesCanAccompanyAConflictWithNoStructuredFields() {
        // Not a case any caller in this codebase uses today, but the constructor allows it
        // independently of fields, so both are exercised: fields absent, fieldCodes present.
        ConflictException ex = new ConflictException("m", null, Map.of("slug", "SLUG_TAKEN"));
        assertNull(ex.getFields());
        assertEquals("SLUG_TAKEN", ex.getFieldCodes().get("slug"));
    }

    @Test
    void nullFieldCodesAreTolerated() {
        assertNull(new ConflictException("m", Map.of("slug", "x"), null).getFieldCodes());
    }
}
