package com.easycrm.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
public abstract class IntegrationTest {

    // Singleton container: started ONCE for the whole JVM and shared across every
    // integration test class. Reliable (one startup, not N) and fast. Testcontainers'
    // ryuk reaper stops it at JVM exit — we never call stop() ourselves.
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("easycrm")
            .withUsername("owner")       // owner role: runs Flyway, owns tables
            .withPassword("owner");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        // Flyway connects as the OWNER (creates the app role in V1).
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        // The application runtime connects as the NON-OWNER app role.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "easycrm_app");
        registry.add("spring.datasource.password", () -> "easycrm_app");
    }
}
