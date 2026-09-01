package com.easycrm.platform.persistence;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BaseEntityTest {
    static class Sample extends BaseEntity {}

    @Test
    void idIsNullBeforePersist() {
        assertNull(new Sample().getId()); // generated on persist, not construction
    }

    @Test
    void baseEntityDeclaresVersionField() throws Exception {
        assertNotNull(BaseEntity.class.getDeclaredField("version"));
        assertNotNull(BaseEntity.class.getDeclaredField("createdAt"));
        assertNotNull(BaseEntity.class.getDeclaredField("updatedAt"));
    }
}
