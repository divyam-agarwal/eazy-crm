package com.easycrm.platform.gst;

import com.easycrm.platform.error.ValidationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GstinTest {

    @Test
    void parsesValidGstinAndExtractsStateCode() {
        Gstin g = Gstin.parse("27AAPFU0939F1ZV");
        assertEquals("27AAPFU0939F1ZV", g.value());
        assertEquals("27", g.stateCode());
    }

    @Test
    void trimsAndUppercases() {
        assertEquals("27AAPFU0939F1ZV", Gstin.parse(" 27aapfu0939f1zv ").value());
    }

    @Test
    void rejectsBadChecksum() {
        ValidationException ex = assertThrows(ValidationException.class,
            () -> Gstin.parse("27AAPFU0939F1ZZ"));
        assertTrue(ex.getFields().containsKey("gstin"));
    }

    @Test
    void rejectsWrongLength() {
        assertThrows(ValidationException.class, () -> Gstin.parse("27AAPFU0939F1Z"));
    }

    @Test
    void stateCodeValidation() {
        assertTrue(StateCode.isValid("27"));
        assertFalse(StateCode.isValid("00"));
        assertFalse(StateCode.isValid("2"));
        assertThrows(ValidationException.class, () -> StateCode.requireValid("88"));
    }
}
