package com.easycrm.support;

import org.junit.jupiter.api.Test;

class HarnessBootTest extends IntegrationTest {
    @Test
    void applicationContextBoots() {
        // If this passes, Testcontainers Postgres + Flyway + JPA all wired correctly.
    }
}
