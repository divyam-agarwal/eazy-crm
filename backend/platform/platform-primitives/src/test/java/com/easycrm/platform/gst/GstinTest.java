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

    @Test
    void rejectsAChecksumValidGstinWithAnInvalidStatePrefix() {
        // checksum-valid (mod-36), but state prefix "88" is not a valid GST state code. Before
        // this check, such a GSTIN parsed cleanly as long as its check digit was consistent, and
        // CustomerService had to validate the prefix on the next line — a two-step the signup
        // path never performed (MF1).
        ValidationException ex = assertThrows(ValidationException.class,
            () -> Gstin.parse("88AAPFU0939F1ZN"));
        assertTrue(ex.getFields().containsKey("stateCode"));
    }

    @Test
    void rejectsNull() {
        assertThrows(ValidationException.class, () -> Gstin.parse(null));
    }

    @Test
    void rejectsFifteenCharactersOutsideTheCharset() {
        // Right length, wrong alphabet: lowercase is uppercased, but punctuation is not a GSTIN
        // character and must not reach the checksum step.
        assertThrows(ValidationException.class, () -> Gstin.parse("27AAPFU0939F1Z*"));
    }

    @Test
    void stateCodeAcceptsEveryValidBoundary() {
        assertTrue(StateCode.isValid("01"));
        assertTrue(StateCode.isValid("38"));
        assertTrue(StateCode.isValid("97"));   // Other Territory
        assertTrue(StateCode.isValid("99"));   // Centre Jurisdiction
        assertFalse(StateCode.isValid("00"));
        assertFalse(StateCode.isValid("39"));
        assertFalse(StateCode.isValid(null));
        assertFalse(StateCode.isValid("1"));
    }
}
