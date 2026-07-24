package com.easycrm.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
public abstract class IntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("easycrm")
            .withUsername("owner")       // owner role: runs Flyway, owns tables
            .withPassword("owner");

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
