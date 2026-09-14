package com.easycrm.platform.gst;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.platform.error.ValidationException;
import org.junit.jupiter.api.Test;

class StateCodeTest {

    @Test
    void anInvalidCodeCarriesAStableReasonCode() {
        ValidationException ex = assertThrows(ValidationException.class, () -> StateCode.requireValid("88"));
        assertEquals("invalid GST state code", ex.getFields().get("stateCode"));
        assertEquals("STATE_CODE_INVALID", ex.getCodes().get("stateCode"));
    }

    @Test
    void anUncodedExceptionHasAnEmptyCodeMap() {
        assertTrue(new ValidationException("x", "y").getCodes().isEmpty());
    }
}
