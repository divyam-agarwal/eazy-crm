package com.easycrm.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

@SpringBootTest
// The limiter is OFF for the suite at large, and this is correctness, not tidiness. Every
// @SpringBootTest sharing this configuration shares ONE cached context, hence one
// RateLimitStore bean, and every MockMvc request originates from the same loopback address.
// Auth-touching requests from ALL test classes would therefore accumulate into a single
// bucket and blow the 30/minute auth policy partway through a suite that runs in ~12s —
// failing on the strength of how many other tests ran first.
//
// This default lives in @TestPropertySource, NOT in the @DynamicPropertySource method below,
// on purpose: @DynamicPropertySource methods declared across a class hierarchy are invoked
// leaf-class-first, THEN superclass (see ReflectionUtils.doWithMethods), writing into one
// last-write-wins map keyed by property name. If "easycrm.rate-limit.enabled" were registered
// here too, THIS class's call would always run last and always win, so no subclass could ever
// turn the limiter back on no matter what it registered. Spring's own precedence rule cuts the
// other way for @TestPropertySource: a @DynamicPropertySource registration unconditionally
// outranks @TestPropertySource, regardless of which class in the hierarchy declares which. So
// a subclass that needs the limiter on (RateLimitIntegrationTest) can register its own
// @DynamicPropertySource for this key and be certain it wins over this default.
// The nightly auto-expiry cron is OFF for the suite, for the same reason the rate limiter
// is: every @SpringBootTest here shares ONE cached context, so a live job would race every
// test class's fixtures and fail intermittently, somewhere else, for reasons that look
// unrelated. "-" is Spring's Scheduled.CRON_DISABLED value, which skips task registration
// entirely rather than scheduling something that never fires.
@TestPropertySource(properties = {
    "easycrm.rate-limit.enabled=false",
    "easycrm.jobs.quotation-expiry.cron=-"
})
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
