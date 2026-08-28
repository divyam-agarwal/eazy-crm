package com.easycrm.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

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
        // The limiter is OFF for the suite at large, and this is correctness, not tidiness.
        // Every @SpringBootTest sharing this configuration shares ONE cached context, hence
        // one RateLimitStore bean, and every MockMvc request originates from the same
        // loopback address. Auth-touching requests from ALL test classes would therefore
        // accumulate into a single bucket and blow the 30/minute auth policy partway
        // through a suite that runs in ~12s — failing on the strength of how many other
        // tests ran first. Rate-limit tests turn it back on with their own tiny limits.
        registry.add("easycrm.rate-limit.enabled", () -> "false");
    }

    /**
     * A connection as the OWNER role — the one Flyway uses, which owns every table.
     * The injectable {@code DataSource} is always the non-owner app role, and the app
     * role has no CREATE on the schema, so a test that needs DDL has to come through
     * here. Container credentials stay private to this class; callers get a connection,
     * not the handle.
     *
     * <p>Caller closes it.
     */
    protected static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
