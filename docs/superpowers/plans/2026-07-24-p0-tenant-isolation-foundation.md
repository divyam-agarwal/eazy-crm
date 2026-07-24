# P0 — Tenant Isolation Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build EasyCRM's backend skeleton with provably-correct multi-tenant data isolation — four independent layers (JWT tenant resolution, Hibernate `@TenantId` filtering, PostgreSQL Row-Level Security, build-time ArchUnit checks) — ending in a runnable two-tenant demo where Tenant A cannot read Tenant B's data (404) and a raw SQL query with no tenant context returns zero rows.

**Architecture:** A single Spring Boot modular-monolith backend. Tenant identity flows from a validated JWT into a `ThreadLocal` `TenantContext`. Hibernate's `@TenantId` auto-filters every scoped query; a custom `JpaTransactionManager` sets a Postgres session variable each transaction so RLS enforces the same rule at the database. An ArchUnit test fails the build if any entity forgets tenant scoping. All isolation is proven against a real, representative tenant-scoped entity (`DemoRecord`) that P1 replaces with real domain entities.

**Tech Stack:** Gradle (Kotlin DSL), Spring Boot 4.1, Java 25 LTS, Hibernate ORM 7, PostgreSQL 16, Flyway, JJWT 0.12, Spring Security, Testcontainers, ArchUnit, JUnit 5.

## Global Constraints

- **Java:** 25 (LTS). `sourceCompatibility`/toolchain = 25.
- **Spring Boot:** 4.1.x. Spring Framework 7, Hibernate ORM 7.4.x (from the Spring Boot BOM — do not pin Hibernate manually).
- **Database:** PostgreSQL 16. Money/ids aside, this plan uses `UUID` primary keys (time-sortable, UUIDv7-style via Hibernate `@UuidGenerator(style = TIME)`).
- **Two DB roles:** migrations run as the **owner** role; the application runtime connects as a **non-owner** role (`easycrm_app`) that does **not** own the tables and does **not** have `BYPASSRLS`. This is what makes RLS real — a table's owner bypasses RLS by default.
- **Tenant source of truth:** the JWT claim `tenant_id` ONLY. Never a header, query param, or subdomain.
- **Cross-tenant access returns 404, never 403** (403 confirms a record exists).
- **`TenantContext` is always cleared** in a `finally` block (pooled threads must not leak).
- **Money is never `double`** (not exercised in P0, but the `BaseEntity`/persistence conventions must not introduce float money). See spec §2.
- **Package root:** `com.easycrm`. Isolation lives under `com.easycrm.platform.tenancy` / `.security` / `.persistence`.
- **Every task ends green** (its tests pass) and is committed.
- **Log challenges:** per `CLAUDE.md`, if a task surfaces a non-obvious problem, append to `docs/superpowers/engineering-challenges.md` in the same commit.

---

## File Structure

```
backend/
  build.gradle.kts
  settings.gradle.kts
  gradle/wrapper/…
  docker-compose.yml                     # local Postgres for manual runs
  src/main/java/com/easycrm/
    EasyCrmApplication.java
    platform/
      tenancy/
        TenantContext.java               # ThreadLocal holder + runAs
        TenantIdentifierResolver.java    # Hibernate CurrentTenantIdentifierResolver<UUID>
        HibernateTenancyConfig.java       # registers the resolver
        TenantAwareTransactionManager.java# sets app.current_tenant via set_config
        TenantAwareTaskDecorator.java     # propagates context to @Async threads
        AsyncConfig.java
      security/
        JwtProperties.java
        JwtService.java                   # parse/validate/mint tokens
        JwtAuthenticationFilter.java      # JWT -> TenantContext + SecurityContext
        SecurityConfig.java               # stateless filter chain
      persistence/
        BaseEntity.java                   # UUIDv7 id, createdAt, updatedAt, version
        TenantScopedEntity.java           # BaseEntity + @TenantId
      error/
        NotFoundException.java
        ApiExceptionHandler.java          # maps to 404 etc.
    tenant/
      Tenant.java                         # GLOBAL entity (no @TenantId)
      TenantRepository.java
    demo/
      DemoRecord.java                     # tenant-scoped, proves isolation
      DemoRecordRepository.java
      DemoRecordController.java           # GET /api/v1/demo-records/{id}
      DemoSeeder.java                     # two-tenant synthetic seed (dev profile)
  src/main/resources/
    application.yml
    application-dev.yml
    db/migration/
      V1__roles_and_extensions.sql
      V2__tenant.sql
      V3__demo_record.sql
      V4__rls_demo_record.sql
  src/test/java/com/easycrm/
    support/
      IntegrationTest.java               # @SpringBootTest + Testcontainers base
      TestTokens.java                     # mint JWTs in tests
    platform/tenancy/
      TenantContextTest.java
      TenantFilteringIntegrationTest.java
      RlsIntegrationTest.java
      TenantAwareTaskDecoratorTest.java
    platform/security/
      JwtServiceTest.java
      SecurityIntegrationTest.java
    demo/
      CrossTenantIsolationIntegrationTest.java  # the headline proof
    arch/
      TenantScopingArchTest.java
```

---

### Task 1: Project scaffold (Gradle + Spring Boot 4.1 + Java 25)

**Files:**
- Create: `backend/settings.gradle.kts`
- Create: `backend/build.gradle.kts`
- Create: `backend/src/main/java/com/easycrm/EasyCrmApplication.java`
- Create: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/easycrm/EasyCrmApplicationTests.java`

**Interfaces:**
- Produces: a bootable Spring Boot app; the Gradle module `backend`.

- [ ] **Step 1: Create the Gradle wrapper and settings**

Run (from repo root):
```bash
mkdir -p backend && cd backend
gradle wrapper --gradle-version 8.14
```
If `gradle` is not installed, install via `brew install gradle` first.

Create `backend/settings.gradle.kts`:
```kotlin
rootProject.name = "easycrm-backend"
```

- [ ] **Step 2: Write the build file**

Create `backend/build.gradle.kts`:
```kotlin
plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.easycrm"
version = "0.0.1-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}

tasks.withType<Test> { useJUnitPlatform() }
```

- [ ] **Step 3: Write the main application class**

Create `backend/src/main/java/com/easycrm/EasyCrmApplication.java`:
```java
package com.easycrm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class EasyCrmApplication {
    public static void main(String[] args) {
        SpringApplication.run(EasyCrmApplication.class, args);
    }
}
```

- [ ] **Step 4: Minimal application config**

Create `backend/src/main/resources/application.yml`:
```yaml
spring:
  application:
    name: easycrm
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        format_sql: true
management:
  endpoints:
    web:
      exposure:
        include: health
```

- [ ] **Step 5: Write the context-loads test**

Create `backend/src/test/java/com/easycrm/EasyCrmApplicationTests.java`:
```java
package com.easycrm;

import org.junit.jupiter.api.Test;

class EasyCrmApplicationTests {
    @Test
    void mainClassExists() {
        // Placeholder smoke test — full @SpringBootTest arrives in Task 2 with Testcontainers.
        org.junit.jupiter.api.Assertions.assertNotNull(EasyCrmApplication.class);
    }
}
```
(We defer `@SpringBootTest` until Task 2 wires a real database — booting JPA without one fails.)

- [ ] **Step 6: Run the test**

Run: `cd backend && ./gradlew test`
Expected: PASS. Build succeeds, dependencies resolve.

- [ ] **Step 7: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/ && git commit -m "chore: scaffold Spring Boot 4.1 backend (Java 25, Gradle)"
```

---

### Task 2: Testcontainers integration harness

**Files:**
- Create: `backend/src/test/java/com/easycrm/support/IntegrationTest.java`
- Create: `backend/docker-compose.yml`
- Modify: `backend/src/main/resources/application.yml` (add datasource + flyway placeholders)
- Test: `backend/src/test/java/com/easycrm/support/HarnessBootTest.java`

**Interfaces:**
- Produces: `IntegrationTest` — a base class annotated `@SpringBootTest` that boots the app against a throwaway PostgreSQL 16 container. Subclasses inherit a fully-migrated DB.

- [ ] **Step 1: Write the integration base class**

Create `backend/src/test/java/com/easycrm/support/IntegrationTest.java`:
```java
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
```

- [ ] **Step 2: Configure datasource + Flyway in application.yml**

Replace `backend/src/main/resources/application.yml` with:
```yaml
spring:
  application:
    name: easycrm
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/easycrm}
    username: ${DB_USER:easycrm_app}
    password: ${DB_PASSWORD:easycrm_app}
  flyway:
    enabled: true
    url: ${FLYWAY_URL:${spring.datasource.url}}
    user: ${FLYWAY_USER:easycrm_owner}
    password: ${FLYWAY_PASSWORD:easycrm_owner}
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        format_sql: true
management:
  endpoints:
    web:
      exposure:
        include: health
```

- [ ] **Step 3: Local docker-compose (for manual runs, not tests)**

Create `backend/docker-compose.yml`:
```yaml
services:
  db:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: easycrm
      POSTGRES_USER: easycrm_owner
      POSTGRES_PASSWORD: easycrm_owner
    ports: ["5432:5432"]
```

- [ ] **Step 4: Write a harness boot test (expected to fail until V1 exists)**

Create `backend/src/test/java/com/easycrm/support/HarnessBootTest.java`:
```java
package com.easycrm.support;

import org.junit.jupiter.api.Test;

class HarnessBootTest extends IntegrationTest {
    @Test
    void applicationContextBoots() {
        // If this passes, Testcontainers Postgres + Flyway + JPA all wired correctly.
    }
}
```

- [ ] **Step 5: Run — verify it fails for the right reason**

Run: `cd backend && ./gradlew test --tests "*HarnessBootTest"`
Expected: FAIL — Flyway finds no migrations / app role `easycrm_app` does not exist yet. This confirms the harness reaches the database. (Task 3 creates the role and first migration.)

- [ ] **Step 6: Commit**

```bash
git add backend/ && git commit -m "test: add Testcontainers integration harness (two DB roles)"
```

---

### Task 3: Flyway V1 — DB roles and extensions

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__roles_and_extensions.sql`

**Interfaces:**
- Produces: a non-owner runtime role `easycrm_app` (login, no `BYPASSRLS`) with default privileges so it can DML future tables; `pgcrypto` available.

- [ ] **Step 1: Write V1 migration**

Create `backend/src/main/resources/db/migration/V1__roles_and_extensions.sql`:
```sql
-- Runtime application role: can log in, but does NOT own tables and has NO BYPASSRLS.
-- Because it is not the table owner, PostgreSQL enforces Row-Level Security against it.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'easycrm_app') THEN
        CREATE ROLE easycrm_app LOGIN PASSWORD 'easycrm_app';
    END IF;
END $$;

-- Let the app role use the schema and DML any tables the owner creates later.
GRANT USAGE ON SCHEMA public TO easycrm_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO easycrm_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO easycrm_app;
```
(No `pgcrypto` needed — UUIDs are generated in Java by Hibernate. Extension line omitted deliberately; keep the migration minimal.)

- [ ] **Step 2: Run the harness test again**

Run: `cd backend && ./gradlew test --tests "*HarnessBootTest"`
Expected: PASS — Flyway (owner) runs V1, app role now exists, context boots.

- [ ] **Step 3: Commit**

```bash
git add backend/ && git commit -m "feat: V1 migration creates non-owner runtime role"
```

---

### Task 4: BaseEntity — UUIDv7 ids + auditing

**Files:**
- Create: `backend/src/main/java/com/easycrm/platform/persistence/BaseEntity.java`
- Modify: `backend/src/main/java/com/easycrm/EasyCrmApplication.java` (enable JPA auditing)
- Test: `backend/src/test/java/com/easycrm/platform/persistence/BaseEntityTest.java`

**Interfaces:**
- Produces: `BaseEntity` mapped superclass with `UUID id` (time-sortable), `Instant createdAt`, `Instant updatedAt`, `long version`. Getter `getId()`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/platform/persistence/BaseEntityTest.java`:
```java
package com.easycrm.platform.persistence;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

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
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./gradlew test --tests "*BaseEntityTest"`
Expected: FAIL — `BaseEntity` does not exist.

- [ ] **Step 3: Write BaseEntity**

Create `backend/src/main/java/com/easycrm/platform/persistence/BaseEntity.java`:
```java
package com.easycrm.platform.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME) // time-sortable (UUIDv7-style)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @Version
    private long version;

    public UUID getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
```

- [ ] **Step 4: Enable JPA auditing**

Edit `backend/src/main/java/com/easycrm/EasyCrmApplication.java` — add the annotation:
```java
package com.easycrm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class EasyCrmApplication {
    public static void main(String[] args) {
        SpringApplication.run(EasyCrmApplication.class, args);
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd backend && ./gradlew test --tests "*BaseEntityTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/ && git commit -m "feat: BaseEntity with UUIDv7 ids, auditing, optimistic locking"
```

---

### Task 5: TenantContext (Layer 1 storage)

**Files:**
- Create: `backend/src/main/java/com/easycrm/platform/tenancy/TenantContext.java`
- Test: `backend/src/test/java/com/easycrm/platform/tenancy/TenantContextTest.java`

**Interfaces:**
- Produces:
  - `record TenantPrincipal(UUID tenantId, UUID userId, String role)`
  - `TenantContext.set(TenantPrincipal)`, `TenantContext.get() : Optional<TenantPrincipal>`, `TenantContext.tenantId() : UUID` (null if unset), `TenantContext.clear()`
  - `TenantContext.runAs(TenantPrincipal, Runnable)` — sets, runs, restores previous.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/platform/tenancy/TenantContextTest.java`:
```java
package com.easycrm.platform.tenancy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TenantContextTest {
    @AfterEach void tearDown() { TenantContext.clear(); }

    @Test
    void unsetByDefault() {
        assertTrue(TenantContext.get().isEmpty());
        assertNull(TenantContext.tenantId());
    }

    @Test
    void setAndGet() {
        UUID t = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(t, UUID.randomUUID(), "OWNER"));
        assertEquals(t, TenantContext.tenantId());
        TenantContext.clear();
        assertNull(TenantContext.tenantId());
    }

    @Test
    void runAsRestoresPrevious() {
        UUID outer = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(outer, UUID.randomUUID(), "OWNER"));
        UUID inner = UUID.randomUUID();
        TenantContext.runAs(new TenantContext.TenantPrincipal(inner, UUID.randomUUID(), "SALES_EXEC"),
            () -> assertEquals(inner, TenantContext.tenantId()));
        assertEquals(outer, TenantContext.tenantId(), "previous context restored after runAs");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./gradlew test --tests "*TenantContextTest"`
Expected: FAIL — `TenantContext` does not exist.

- [ ] **Step 3: Write TenantContext**

Create `backend/src/main/java/com/easycrm/platform/tenancy/TenantContext.java`:
```java
package com.easycrm.platform.tenancy;

import java.util.Optional;
import java.util.UUID;

public final class TenantContext {

    public record TenantPrincipal(UUID tenantId, UUID userId, String role) {}

    private static final ThreadLocal<TenantPrincipal> HOLDER = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(TenantPrincipal principal) { HOLDER.set(principal); }

    public static Optional<TenantPrincipal> get() { return Optional.ofNullable(HOLDER.get()); }

    public static UUID tenantId() {
        TenantPrincipal p = HOLDER.get();
        return p == null ? null : p.tenantId();
    }

    public static void clear() { HOLDER.remove(); }

    public static void runAs(TenantPrincipal principal, Runnable body) {
        TenantPrincipal previous = HOLDER.get();
        HOLDER.set(principal);
        try {
            body.run();
        } finally {
            if (previous == null) HOLDER.remove(); else HOLDER.set(previous);
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./gradlew test --tests "*TenantContextTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/ && git commit -m "feat: TenantContext ThreadLocal holder with runAs"
```

---

### Task 6: Tenant entity (a GLOBAL table)

**Files:**
- Create: `backend/src/main/java/com/easycrm/tenant/Tenant.java`
- Create: `backend/src/main/java/com/easycrm/tenant/TenantRepository.java`
- Create: `backend/src/main/resources/db/migration/V2__tenant.sql`
- Test: `backend/src/test/java/com/easycrm/tenant/TenantRepositoryTest.java`

**Interfaces:**
- Produces: `Tenant` entity (extends `BaseEntity`, **no** `@TenantId` — it is the tenant, not scoped by one), fields `slug`, `businessName`, `stateCode`. `TenantRepository extends JpaRepository<Tenant, UUID>` with `Optional<Tenant> findBySlug(String)`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/tenant/TenantRepositoryTest.java`:
```java
package com.easycrm.tenant;

import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.junit.jupiter.api.Assertions.*;

class TenantRepositoryTest extends IntegrationTest {
    @Autowired TenantRepository tenants;

    @Test
    void savesAndFindsBySlug() {
        Tenant t = new Tenant("acme-traders", "Acme Traders", "27");
        tenants.save(t);
        assertNotNull(t.getId());
        assertTrue(tenants.findBySlug("acme-traders").isPresent());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./gradlew test --tests "*TenantRepositoryTest"`
Expected: FAIL — `Tenant` does not exist.

- [ ] **Step 3: Write the entity, repository, migration**

Create `backend/src/main/java/com/easycrm/tenant/Tenant.java`:
```java
package com.easycrm.tenant;

import com.easycrm.platform.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "tenant")
public class Tenant extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(name = "business_name", nullable = false)
    private String businessName;

    @Column(name = "state_code", nullable = false, length = 2)
    private String stateCode;

    protected Tenant() {} // JPA

    public Tenant(String slug, String businessName, String stateCode) {
        this.slug = slug;
        this.businessName = businessName;
        this.stateCode = stateCode;
    }

    public String getSlug() { return slug; }
    public String getBusinessName() { return businessName; }
    public String getStateCode() { return stateCode; }
}
```

Create `backend/src/main/java/com/easycrm/tenant/TenantRepository.java`:
```java
package com.easycrm.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    Optional<Tenant> findBySlug(String slug);
}
```

Create `backend/src/main/resources/db/migration/V2__tenant.sql`:
```sql
CREATE TABLE tenant (
    id            UUID PRIMARY KEY,
    slug          VARCHAR(64) NOT NULL UNIQUE,
    business_name VARCHAR(255) NOT NULL,
    state_code    CHAR(2) NOT NULL,
    created_at    TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ,
    version       BIGINT NOT NULL DEFAULT 0
);
-- tenant is a GLOBAL table: no tenant_id, no RLS. It is the tenant registry itself.
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./gradlew test --tests "*TenantRepositoryTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/ && git commit -m "feat: Tenant global entity + registry table"
```

---

### Task 7: TenantScopedEntity + DemoRecord (the isolation subject)

**Files:**
- Create: `backend/src/main/java/com/easycrm/platform/persistence/TenantScopedEntity.java`
- Create: `backend/src/main/java/com/easycrm/demo/DemoRecord.java`
- Create: `backend/src/main/java/com/easycrm/demo/DemoRecordRepository.java`
- Create: `backend/src/main/resources/db/migration/V3__demo_record.sql`
- Test: (covered by Task 8's filtering test — no standalone test here; this task only introduces types)

**Interfaces:**
- Produces:
  - `TenantScopedEntity` — extends `BaseEntity`, adds `@TenantId private UUID tenantId;` with `getTenantId()`.
  - `DemoRecord` — extends `TenantScopedEntity`, field `String label`. Ctor `DemoRecord(String label)`.
  - `DemoRecordRepository extends JpaRepository<DemoRecord, UUID>`.

- [ ] **Step 1: Write TenantScopedEntity**

Create `backend/src/main/java/com/easycrm/platform/persistence/TenantScopedEntity.java`:
```java
package com.easycrm.platform.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.TenantId;
import java.util.UUID;

@MappedSuperclass
public abstract class TenantScopedEntity extends BaseEntity {

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    public UUID getTenantId() { return tenantId; }
}
```

- [ ] **Step 2: Write DemoRecord + repository**

Create `backend/src/main/java/com/easycrm/demo/DemoRecord.java`:
```java
package com.easycrm.demo;

import com.easycrm.platform.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "demo_record")
public class DemoRecord extends TenantScopedEntity {

    @Column(nullable = false)
    private String label;

    protected DemoRecord() {}

    public DemoRecord(String label) { this.label = label; }

    public String getLabel() { return label; }
}
```

Create `backend/src/main/java/com/easycrm/demo/DemoRecordRepository.java`:
```java
package com.easycrm.demo;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface DemoRecordRepository extends JpaRepository<DemoRecord, UUID> {}
```

- [ ] **Step 3: Write the migration**

Create `backend/src/main/resources/db/migration/V3__demo_record.sql`:
```sql
CREATE TABLE demo_record (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL,
    label      VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    version    BIGINT NOT NULL DEFAULT 0
);
-- tenant_id is the leading column of this index: the RLS predicate (added in V4)
-- is a plain indexed equality filter, so the planner treats it as a normal filter.
CREATE INDEX idx_demo_record_tenant ON demo_record (tenant_id, id);
```

- [ ] **Step 4: Compile check**

Run: `cd backend && ./gradlew compileJava`
Expected: PASS. (Behavioural verification is Task 8.)

- [ ] **Step 5: Commit**

```bash
git add backend/ && git commit -m "feat: TenantScopedEntity + DemoRecord isolation subject"
```

---

### Task 8: Hibernate @TenantId resolver (Layer 2)

**Files:**
- Create: `backend/src/main/java/com/easycrm/platform/tenancy/TenantIdentifierResolver.java`
- Create: `backend/src/main/java/com/easycrm/platform/tenancy/HibernateTenancyConfig.java`
- Test: `backend/src/test/java/com/easycrm/platform/tenancy/TenantFilteringIntegrationTest.java`

**Interfaces:**
- Consumes: `TenantContext.tenantId()`.
- Produces: a `CurrentTenantIdentifierResolver<UUID>` bean wired into Hibernate so `@TenantId` auto-fills on insert and auto-filters on read. When no tenant is set, resolves to a NIL UUID (`00000000-0000-0000-0000-000000000000`) — a value no real tenant owns, so scoped reads return nothing.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/platform/tenancy/TenantFilteringIntegrationTest.java`:
```java
package com.easycrm.platform.tenancy;

import com.easycrm.demo.DemoRecord;
import com.easycrm.demo.DemoRecordRepository;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TenantFilteringIntegrationTest extends IntegrationTest {
    @Autowired DemoRecordRepository records;

    @AfterEach void clear() { TenantContext.clear(); }

    private void asTenant(UUID t) {
        TenantContext.set(new TenantContext.TenantPrincipal(t, UUID.randomUUID(), "OWNER"));
    }

    @Test
    void tenantIdAutoPopulatedOnInsert() {
        UUID a = UUID.randomUUID();
        asTenant(a);
        DemoRecord saved = records.save(new DemoRecord("a-1"));
        assertEquals(a, saved.getTenantId(), "@TenantId auto-filled from context");
    }

    @Test
    void readsAreFilteredByTenant() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        asTenant(a); records.save(new DemoRecord("a-1"));
        asTenant(b); records.save(new DemoRecord("b-1"));

        asTenant(a);
        assertEquals(1, records.findAll().size(), "tenant A sees only its own row");
        assertEquals("a-1", records.findAll().get(0).getLabel());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./gradlew test --tests "*TenantFilteringIntegrationTest"`
Expected: FAIL — no resolver registered; `@TenantId` has no current identifier.

- [ ] **Step 3: Write the resolver**

Create `backend/src/main/java/com/easycrm/platform/tenancy/TenantIdentifierResolver.java`:
```java
package com.easycrm.platform.tenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID> {

    /** No real tenant owns the NIL UUID, so scoped queries with no context match nothing. */
    public static final UUID NO_TENANT = new UUID(0L, 0L);

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        UUID t = TenantContext.tenantId();
        return t != null ? t : NO_TENANT;
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}
```

- [ ] **Step 4: Register the resolver with Hibernate**

Create `backend/src/main/java/com/easycrm/platform/tenancy/HibernateTenancyConfig.java`:
```java
package com.easycrm.platform.tenancy;

import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HibernateTenancyConfig {

    @Bean
    HibernatePropertiesCustomizer tenancyCustomizer(TenantIdentifierResolver resolver) {
        return props -> props.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd backend && ./gradlew test --tests "*TenantFilteringIntegrationTest"`
Expected: PASS — inserts auto-fill `tenant_id`; reads filtered to current tenant.

- [ ] **Step 6: Commit**

```bash
git add backend/ && git commit -m "feat: Hibernate @TenantId resolver (Layer 2 auto-filtering)"
```

---

### Task 9: JWT service + auth filter (Layer 1 resolution)

**Files:**
- Create: `backend/src/main/java/com/easycrm/platform/security/JwtProperties.java`
- Create: `backend/src/main/java/com/easycrm/platform/security/JwtService.java`
- Create: `backend/src/main/java/com/easycrm/platform/security/JwtAuthenticationFilter.java`
- Modify: `backend/src/main/resources/application.yml` (jwt secret)
- Test: `backend/src/test/java/com/easycrm/platform/security/JwtServiceTest.java`
- Test helper: `backend/src/test/java/com/easycrm/support/TestTokens.java`

**Interfaces:**
- Produces:
  - `JwtService.mint(UUID tenantId, UUID userId, String role) : String`
  - `JwtService.parse(String token) : TenantContext.TenantPrincipal` (throws on invalid/expired)
  - `JwtAuthenticationFilter` — a `OncePerRequestFilter` that reads `Authorization: Bearer`, parses, sets `TenantContext` + Spring `SecurityContext`, and **clears TenantContext in a finally block**.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/platform/security/JwtServiceTest.java`:
```java
package com.easycrm.platform.security;

import com.easycrm.platform.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {
    private final JwtService jwt = new JwtService(
        new JwtProperties("0123456789-0123456789-0123456789-secret", 900));

    @Test
    void mintThenParseRoundTrips() {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID();
        String token = jwt.mint(tenant, user, "OWNER");
        TenantContext.TenantPrincipal p = jwt.parse(token);
        assertEquals(tenant, p.tenantId());
        assertEquals(user, p.userId());
        assertEquals("OWNER", p.role());
    }

    @Test
    void tamperedTokenRejected() {
        String token = jwt.mint(UUID.randomUUID(), UUID.randomUUID(), "OWNER");
        assertThrows(RuntimeException.class, () -> jwt.parse(token + "x"));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./gradlew test --tests "*JwtServiceTest"`
Expected: FAIL — `JwtService`/`JwtProperties` do not exist.

- [ ] **Step 3: Write JwtProperties + JwtService**

Create `backend/src/main/java/com/easycrm/platform/security/JwtProperties.java`:
```java
package com.easycrm.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "easycrm.jwt")
public record JwtProperties(String secret, long accessTtlSeconds) {}
```

Create `backend/src/main/java/com/easycrm/platform/security/JwtService.java`:
```java
package com.easycrm.platform.security;

import com.easycrm.platform.tenancy.TenantContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final long accessTtlSeconds;

    public JwtService(JwtProperties props) {
        this.key = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
        this.accessTtlSeconds = props.accessTtlSeconds();
    }

    public String mint(UUID tenantId, UUID userId, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(userId.toString())
            .claim("tenant_id", tenantId.toString())
            .claim("role", role)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(accessTtlSeconds)))
            .signWith(key)
            .compact();
    }

    public TenantContext.TenantPrincipal parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).build()
            .parseSignedClaims(token).getPayload();
        return new TenantContext.TenantPrincipal(
            UUID.fromString(c.get("tenant_id", String.class)),
            UUID.fromString(c.getSubject()),
            c.get("role", String.class));
    }
}
```

- [ ] **Step 4: Add secret to config + enable @ConfigurationProperties**

Add to `backend/src/main/resources/application.yml` (top-level, under root):
```yaml
easycrm:
  jwt:
    secret: ${JWT_SECRET:0123456789-0123456789-0123456789-devsecret}
    access-ttl-seconds: 900
```
Add to `EasyCrmApplication.java` the annotation `@ConfigurationPropertiesScan`:
```java
package com.easycrm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableJpaAuditing
public class EasyCrmApplication {
    public static void main(String[] args) {
        SpringApplication.run(EasyCrmApplication.class, args);
    }
}
```

- [ ] **Step 5: Run to verify JwtServiceTest passes**

Run: `cd backend && ./gradlew test --tests "*JwtServiceTest"`
Expected: PASS.

- [ ] **Step 6: Write the auth filter**

Create `backend/src/main/java/com/easycrm/platform/security/JwtAuthenticationFilter.java`:
```java
package com.easycrm.platform.security;

import com.easycrm.platform.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwt;

    public JwtAuthenticationFilter(JwtService jwt) { this.jwt = jwt; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        try {
            if (header != null && header.startsWith("Bearer ")) {
                TenantContext.TenantPrincipal p = jwt.parse(header.substring(7));
                TenantContext.set(p);
                var auth = new UsernamePasswordAuthenticationToken(
                    p.userId(), null, List.of(new SimpleGrantedAuthority("ROLE_" + p.role())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            chain.doFilter(req, res);
        } catch (RuntimeException ex) {
            // invalid token: leave unauthenticated; SecurityConfig will 401 protected routes
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();           // MUST clear — pooled threads
            SecurityContextHolder.clearContext();
        }
    }
}
```

- [ ] **Step 7: Write the test-token helper**

Create `backend/src/test/java/com/easycrm/support/TestTokens.java`:
```java
package com.easycrm.support;

import com.easycrm.platform.security.JwtService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class TestTokens {
    @Autowired JwtService jwt;
    public String owner(UUID tenantId) {
        return jwt.mint(tenantId, UUID.randomUUID(), "OWNER");
    }
}
```

- [ ] **Step 8: Run full build (filter has no standalone test yet; verified in Task 10)**

Run: `cd backend && ./gradlew test`
Expected: PASS (all prior tests still green).

- [ ] **Step 9: Commit**

```bash
git add backend/ && git commit -m "feat: JWT service + auth filter setting TenantContext (Layer 1)"
```

---

### Task 10: Spring Security filter chain

**Files:**
- Create: `backend/src/main/java/com/easycrm/platform/security/SecurityConfig.java`
- Test: `backend/src/test/java/com/easycrm/platform/security/SecurityIntegrationTest.java`

**Interfaces:**
- Consumes: `JwtAuthenticationFilter`.
- Produces: a stateless `SecurityFilterChain` — `/actuator/health` public, everything under `/api/**` requires authentication; JWT filter runs before `UsernamePasswordAuthenticationFilter`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/platform/security/SecurityIntegrationTest.java`:
```java
package com.easycrm.platform.security;

import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.autoconfigure.AutoConfigureMockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityIntegrationTest extends IntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    void protectedRouteWithoutTokenIs401() throws Exception {
        mvc.perform(get("/api/v1/demo-records/00000000-0000-0000-0000-000000000000"))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./gradlew test --tests "*SecurityIntegrationTest"`
Expected: FAIL — no security config; default Spring Security may 401 health too / route returns wrong status.

- [ ] **Step 3: Write SecurityConfig**

Create `backend/src/main/java/com/easycrm/platform/security/SecurityConfig.java`:
```java
package com.easycrm.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./gradlew test --tests "*SecurityIntegrationTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/ && git commit -m "feat: stateless SecurityFilterChain with JWT filter"
```

---

### Task 11: RLS via TenantAwareTransactionManager (Layer 3)

**Files:**
- Create: `backend/src/main/java/com/easycrm/platform/tenancy/TenantAwareTransactionManager.java`
- Create: `backend/src/main/java/com/easycrm/platform/tenancy/TransactionManagerConfig.java`
- Create: `backend/src/main/resources/db/migration/V4__rls_demo_record.sql`
- Test: `backend/src/test/java/com/easycrm/platform/tenancy/RlsIntegrationTest.java`

**Interfaces:**
- Consumes: `TenantContext.tenantId()`, the JPA `EntityManagerFactory`.
- Produces: a primary `JpaTransactionManager` that, on transaction begin, runs `SELECT set_config('app.current_tenant', :tid, true)` when a tenant is set — making the Postgres RLS policy enforce isolation for the non-owner app role.

- [ ] **Step 1: Write the RLS migration**

Create `backend/src/main/resources/db/migration/V4__rls_demo_record.sql`:
```sql
ALTER TABLE demo_record ENABLE ROW LEVEL SECURITY;

-- The app connects as the non-owner role easycrm_app, so this policy is enforced.
-- current_setting(..., true) returns NULL when unset -> tenant_id = NULL -> no rows.
CREATE POLICY tenant_isolation ON demo_record
    USING (tenant_id = current_setting('app.current_tenant', true)::uuid);
```

- [ ] **Step 2: Write the failing test**

Create `backend/src/test/java/com/easycrm/platform/tenancy/RlsIntegrationTest.java`:
```java
package com.easycrm.platform.tenancy;

import com.easycrm.demo.DemoRecord;
import com.easycrm.demo.DemoRecordRepository;
import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RlsIntegrationTest extends IntegrationTest {
    @Autowired DemoRecordRepository records;
    @Autowired DataSource dataSource; // app (non-owner) datasource

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void rawQueryWithoutTenantContextReturnsZeroRows() throws Exception {
        UUID a = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(a, UUID.randomUUID(), "OWNER"));
        records.saveAndFlush(new DemoRecord("a-1"));
        TenantContext.clear();

        // Fresh connection from the app (non-owner) pool, no app.current_tenant set.
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM demo_record")) {
            rs.next();
            assertEquals(0, rs.getInt(1),
                "RLS blocks the non-owner app role when no tenant is set");
        }
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd backend && ./gradlew test --tests "*RlsIntegrationTest"`
Expected: FAIL — without the custom tx manager, either the row is visible (RLS not enforced because app.current_tenant never set / owner connection) or the count is non-zero.

- [ ] **Step 4: Write the transaction manager**

Create `backend/src/main/java/com/easycrm/platform/tenancy/TenantAwareTransactionManager.java`:
```java
package com.easycrm.platform.tenancy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Sets a transaction-scoped Postgres GUC (app.current_tenant) so Row-Level Security
 * enforces tenant isolation at the database. Uses set_config(..., is_local => true)
 * because SET LOCAL cannot take a bind parameter; is_local=true clears it at
 * commit/rollback, so no value leaks back into the pooled connection.
 */
public class TenantAwareTransactionManager extends JpaTransactionManager {

    public TenantAwareTransactionManager(EntityManagerFactory emf) { super(emf); }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        UUID tenantId = TenantContext.tenantId();
        if (tenantId == null) return; // leave GUC unset -> scoped tables see zero rows

        EntityManagerHolder holder =
            (EntityManagerHolder) TransactionSynchronizationManager.getResource(getEntityManagerFactory());
        if (holder == null) return;
        EntityManager em = holder.getEntityManager();
        em.createNativeQuery("SELECT set_config('app.current_tenant', :tid, true)")
          .setParameter("tid", tenantId.toString())
          .getSingleResult();
    }
}
```

Create `backend/src/main/java/com/easycrm/platform/tenancy/TransactionManagerConfig.java`:
```java
package com.easycrm.platform.tenancy;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class TransactionManagerConfig {

    @Bean
    @Primary
    PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new TenantAwareTransactionManager(emf);
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd backend && ./gradlew test --tests "*RlsIntegrationTest"`
Expected: PASS — raw count from the app role with no tenant set is 0.

- [ ] **Step 6: Run the full suite (ensure filtering test still green under the new tx manager)**

Run: `cd backend && ./gradlew test`
Expected: PASS.

- [ ] **Step 7: Commit + log the challenge**

Append the RLS-with-connection-pooling detail (set_config vs SET LOCAL) to `docs/superpowers/engineering-challenges.md` if implementation revealed anything beyond the design note, then:
```bash
git add backend/ docs/ && git commit -m "feat: RLS enforcement via TenantAwareTransactionManager (Layer 3)"
```

---

### Task 12: DemoRecord read endpoint + 404 mapping

**Files:**
- Create: `backend/src/main/java/com/easycrm/platform/error/NotFoundException.java`
- Create: `backend/src/main/java/com/easycrm/platform/error/ApiExceptionHandler.java`
- Create: `backend/src/main/java/com/easycrm/demo/DemoRecordController.java`
- Test: (covered by the headline Task 14 test; a focused test here for the happy path)
- Test: `backend/src/test/java/com/easycrm/demo/DemoRecordControllerTest.java`

**Interfaces:**
- Consumes: `DemoRecordRepository`, `TestTokens` (tests).
- Produces: `GET /api/v1/demo-records/{id}` → `200 {id,label}` if visible to the caller's tenant, else `404`. `NotFoundException` mapped to HTTP 404 by `ApiExceptionHandler`.

- [ ] **Step 1: Write the failing test (happy path + own-tenant)**

Create `backend/src/test/java/com/easycrm/demo/DemoRecordControllerTest.java`:
```java
package com.easycrm.demo;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.autoconfigure.AutoConfigureMockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class DemoRecordControllerTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DemoRecordRepository records;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void ownerCanReadOwnRecord() throws Exception {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(tenant, UUID.randomUUID(), "OWNER"));
        DemoRecord saved = records.saveAndFlush(new DemoRecord("mine"));
        TenantContext.clear();

        mvc.perform(get("/api/v1/demo-records/" + saved.getId())
                .header("Authorization", "Bearer " + tokens.owner(tenant)))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.label").value("mine"));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./gradlew test --tests "*DemoRecordControllerTest"`
Expected: FAIL — controller does not exist (404/500).

- [ ] **Step 3: Write the exception + handler + controller**

Create `backend/src/main/java/com/easycrm/platform/error/NotFoundException.java`:
```java
package com.easycrm.platform.error;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) { super(message); }
}
```

Create `backend/src/main/java/com/easycrm/platform/error/ApiExceptionHandler.java`:
```java
package com.easycrm.platform.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("error", Map.of("code", "NOT_FOUND", "message", ex.getMessage())));
    }
}
```

Create `backend/src/main/java/com/easycrm/demo/DemoRecordController.java`:
```java
package com.easycrm.demo;

import com.easycrm.platform.error.NotFoundException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/demo-records")
public class DemoRecordController {

    private final DemoRecordRepository records;

    public DemoRecordController(DemoRecordRepository records) { this.records = records; }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        // findById is tenant-filtered by @TenantId AND row-secured by RLS.
        // A record owned by another tenant is simply not found -> 404, not 403.
        DemoRecord r = records.findById(id)
            .orElseThrow(() -> new NotFoundException("demo record not found"));
        return Map.of("id", r.getId(), "label", r.getLabel());
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./gradlew test --tests "*DemoRecordControllerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/ && git commit -m "feat: demo-records read endpoint + 404 exception mapping"
```

---

### Task 13: TenantAwareTaskDecorator (async propagation)

**Files:**
- Create: `backend/src/main/java/com/easycrm/platform/tenancy/TenantAwareTaskDecorator.java`
- Create: `backend/src/main/java/com/easycrm/platform/tenancy/AsyncConfig.java`
- Test: `backend/src/test/java/com/easycrm/platform/tenancy/TenantAwareTaskDecoratorTest.java`

**Interfaces:**
- Produces: a `TaskDecorator` that captures the submitting thread's `TenantPrincipal` and restores it on the worker thread (clearing afterward). Wired into the default `@Async` executor.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/platform/tenancy/TenantAwareTaskDecoratorTest.java`:
```java
package com.easycrm.platform.tenancy;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class TenantAwareTaskDecoratorTest {

    @Test
    void contextPropagatesToWorkerThread() throws Exception {
        UUID tenant = UUID.randomUUID();
        TenantContext.set(new TenantContext.TenantPrincipal(tenant, UUID.randomUUID(), "OWNER"));

        TenantAwareTaskDecorator decorator = new TenantAwareTaskDecorator();
        Executor pool = Executors.newSingleThreadExecutor();

        CompletableFuture<UUID> seen = new CompletableFuture<>();
        pool.execute(decorator.decorate(() -> seen.complete(TenantContext.tenantId())));

        assertEquals(tenant, seen.get(), "worker thread sees the submitter's tenant");
        TenantContext.clear();
    }

    @Test
    void workerThreadContextClearedAfterRun() throws Exception {
        TenantAwareTaskDecorator decorator = new TenantAwareTaskDecorator();
        Executor pool = Executors.newSingleThreadExecutor();

        TenantContext.set(new TenantContext.TenantPrincipal(UUID.randomUUID(), UUID.randomUUID(), "OWNER"));
        pool.execute(decorator.decorate(() -> {}));
        Thread.sleep(50);

        CompletableFuture<UUID> after = new CompletableFuture<>();
        pool.execute(() -> after.complete(TenantContext.tenantId()));
        assertNull(after.get(), "no tenant leaked into the pooled worker thread");
        TenantContext.clear();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./gradlew test --tests "*TenantAwareTaskDecoratorTest"`
Expected: FAIL — `TenantAwareTaskDecorator` does not exist.

- [ ] **Step 3: Write the decorator + async config**

Create `backend/src/main/java/com/easycrm/platform/tenancy/TenantAwareTaskDecorator.java`:
```java
package com.easycrm.platform.tenancy;

import org.springframework.core.task.TaskDecorator;

public class TenantAwareTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable runnable) {
        TenantContext.TenantPrincipal captured = TenantContext.get().orElse(null);
        return () -> {
            if (captured != null) TenantContext.set(captured);
            try {
                runnable.run();
            } finally {
                TenantContext.clear();
            }
        };
    }
}
```

Create `backend/src/main/java/com/easycrm/platform/tenancy/AsyncConfig.java`:
```java
package com.easycrm.platform.tenancy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean
    ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setTaskDecorator(new TenantAwareTaskDecorator());
        executor.initialize();
        return executor;
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./gradlew test --tests "*TenantAwareTaskDecoratorTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/ && git commit -m "feat: TenantAwareTaskDecorator propagates context to @Async threads"
```

---

### Task 14: Cross-tenant isolation — the headline proof

**Files:**
- Test: `backend/src/test/java/com/easycrm/demo/CrossTenantIsolationIntegrationTest.java`

**Interfaces:**
- Consumes: `DemoRecordController` via MockMvc, `TestTokens`, `DemoRecordRepository`, `TenantContext`.
- Produces: the regression test that guards the demo — Tenant A cannot read Tenant B's record (404).

- [ ] **Step 1: Write the cross-tenant test**

Create `backend/src/test/java/com/easycrm/demo/CrossTenantIsolationIntegrationTest.java`:
```java
package com.easycrm.demo;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.autoconfigure.AutoConfigureMockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CrossTenantIsolationIntegrationTest extends IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired DemoRecordRepository records;
    @Autowired TestTokens tokens;

    @AfterEach void clear() { TenantContext.clear(); }

    private UUID saveFor(UUID tenant, String label) {
        TenantContext.set(new TenantContext.TenantPrincipal(tenant, UUID.randomUUID(), "OWNER"));
        UUID id = records.saveAndFlush(new DemoRecord(label)).getId();
        TenantContext.clear();
        return id;
    }

    @Test
    void tenantACannotReadTenantBRecord_returns404NotFound() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID bRecordId = saveFor(tenantB, "b-secret");

        // Authenticated as A, requesting B's real record id -> must be 404, never 403.
        mvc.perform(get("/api/v1/demo-records/" + bRecordId)
                .header("Authorization", "Bearer " + tokens.owner(tenantA)))
           .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run to verify it passes**

Run: `cd backend && ./gradlew test --tests "*CrossTenantIsolationIntegrationTest"`
Expected: PASS — A gets 404 for B's record.

- [ ] **Step 3: Commit**

```bash
git add backend/ && git commit -m "test: cross-tenant 404 regression (the headline isolation proof)"
```

---

### Task 15: ArchUnit rule (Layer 4)

**Files:**
- Create: `backend/src/test/java/com/easycrm/arch/TenantScopingArchTest.java`

**Interfaces:**
- Consumes: all `@Entity` classes under `com.easycrm`.
- Produces: a build-failing rule — every `@Entity` must extend `TenantScopedEntity` (i.e. declare `@TenantId`) unless explicitly listed in `GLOBAL_TABLES`.

- [ ] **Step 1: Write the rule (it should PASS against the current codebase)**

Create `backend/src/test/java/com/easycrm/arch/TenantScopingArchTest.java`:
```java
package com.easycrm.arch;

import com.easycrm.platform.persistence.TenantScopedEntity;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

class TenantScopingArchTest {

    /** Global tables are intentionally NOT tenant-scoped. Add here only with review. */
    private static final Set<String> GLOBAL_TABLES = Set.of(
        "com.easycrm.tenant.Tenant"
    );

    @Test
    void everyEntityIsTenantScopedUnlessAllowlisted() {
        JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.easycrm");

        ArchRule rule = classes()
            .that().areAnnotatedWith(Entity.class)
            .and().haveNameNotMatching(escaped(GLOBAL_TABLES))
            .should().beAssignableTo(TenantScopedEntity.class)
            .because("every tenant-scoped @Entity must declare @TenantId via TenantScopedEntity; "
                   + "global tables must be added to GLOBAL_TABLES with explicit review");

        rule.check(classes);
    }

    private static String escaped(Set<String> names) {
        return names.stream().map(java.util.regex.Pattern::quote)
            .reduce((a, b) -> a + "|" + b).map(s -> "(" + s + ")").orElse("(?!)");
    }
}
```

- [ ] **Step 2: Run to verify it passes**

Run: `cd backend && ./gradlew test --tests "*TenantScopingArchTest"`
Expected: PASS — `Tenant` is allowlisted; `DemoRecord` extends `TenantScopedEntity`.

- [ ] **Step 3: Prove the rule bites (temporary experiment — do NOT commit the bad entity)**

Temporarily create `backend/src/main/java/com/easycrm/demo/LeakyRecord.java`:
```java
package com.easycrm.demo;

import com.easycrm.platform.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity @Table(name = "leaky_record")
public class LeakyRecord extends BaseEntity {}  // NOT tenant-scoped, NOT allowlisted
```
Run: `cd backend && ./gradlew test --tests "*TenantScopingArchTest"`
Expected: FAIL — the rule flags `LeakyRecord`. This proves Layer 4 works.
Then **delete** the file:
```bash
rm backend/src/main/java/com/easycrm/demo/LeakyRecord.java
```
Re-run: `cd backend && ./gradlew test --tests "*TenantScopingArchTest"` → PASS again.

- [ ] **Step 4: Commit**

```bash
git add backend/ && git commit -m "test: ArchUnit rule fails build on non-tenant-scoped entity (Layer 4)"
```

---

### Task 16: Two-tenant seed + demo README (portfolio asset)

**Files:**
- Create: `backend/src/main/java/com/easycrm/demo/DemoSeeder.java`
- Create: `backend/src/main/resources/application-dev.yml`
- Create: `backend/DEMO.md`
- Test: `backend/src/test/java/com/easycrm/demo/DemoSeederTest.java`

**Interfaces:**
- Consumes: `TenantRepository`, `DemoRecordRepository`, `TenantContext`.
- Produces: on the `dev` profile, two synthetic tenants (checksum-valid-but-fake GSTINs, clearly labelled synthetic) each with demo records; a `DEMO.md` reproducing the 404 + zero-rows walkthrough by hand.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/demo/DemoSeederTest.java`:
```java
package com.easycrm.demo;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import com.easycrm.tenant.Tenant;
import com.easycrm.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DemoSeederTest extends IntegrationTest {
    @Autowired TenantRepository tenants;
    @Autowired DemoRecordRepository records;

    @AfterEach void clear() { TenantContext.clear(); }

    @Test
    void seedsTwoTenantsEachWithRecords() {
        new DemoSeeder(tenants, records).seed();

        Tenant a = tenants.findBySlug("alpha-traders").orElseThrow();
        Tenant b = tenants.findBySlug("bravo-distributors").orElseThrow();
        assertNotEquals(a.getId(), b.getId());

        TenantContext.set(new TenantContext.TenantPrincipal(a.getId(), UUID.randomUUID(), "OWNER"));
        assertFalse(records.findAll().isEmpty(), "tenant A has seeded records");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./gradlew test --tests "*DemoSeederTest"`
Expected: FAIL — `DemoSeeder` does not exist.

- [ ] **Step 3: Write the seeder**

Create `backend/src/main/java/com/easycrm/demo/DemoSeeder.java`:
```java
package com.easycrm.demo;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.tenant.Tenant;
import com.easycrm.tenant.TenantRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** SYNTHETIC demo data only. GSTINs are checksum-valid but fabricated. */
@Component
@Profile("dev")
public class DemoSeeder implements CommandLineRunner {

    private final TenantRepository tenants;
    private final DemoRecordRepository records;

    public DemoSeeder(TenantRepository tenants, DemoRecordRepository records) {
        this.tenants = tenants;
        this.records = records;
    }

    @Override
    public void run(String... args) { seed(); }

    @Transactional
    public void seed() {
        if (tenants.findBySlug("alpha-traders").isPresent()) return; // idempotent
        Tenant a = tenants.save(new Tenant("alpha-traders", "Alpha Traders (SYNTHETIC)", "27"));
        Tenant b = tenants.save(new Tenant("bravo-distributors", "Bravo Distributors (SYNTHETIC)", "29"));
        seedRecordsFor(a.getId(), "Alpha");
        seedRecordsFor(b.getId(), "Bravo");
    }

    private void seedRecordsFor(UUID tenantId, String prefix) {
        TenantContext.runAs(new TenantContext.TenantPrincipal(tenantId, UUID.randomUUID(), "OWNER"),
            () -> {
                records.save(new DemoRecord(prefix + " confidential record 1"));
                records.save(new DemoRecord(prefix + " confidential record 2"));
            });
    }
}
```
Note: because `seedRecordsFor` needs RLS `app.current_tenant` set, and this runs under the `TenantAwareTransactionManager`, the `@Transactional seed()` establishes the tenant per inner `runAs`. If running as the `dev` CommandLineRunner against the app (non-owner) datasource, keep the outer `@Transactional` and rely on `TenantContext` being set before each save; for the seed path we accept a per-tenant transaction is cleanest — if flush fails under RLS, split `seedRecordsFor` to run in its own transactional method. (Verified by `DemoSeederTest`, which sets context explicitly.)

- [ ] **Step 4: dev profile config**

Create `backend/src/main/resources/application-dev.yml`:
```yaml
spring:
  config:
    activate:
      on-profile: dev
  jpa:
    show-sql: true
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd backend && ./gradlew test --tests "*DemoSeederTest"`
Expected: PASS.

- [ ] **Step 6: Write DEMO.md (the manual walkthrough)**

Create `backend/DEMO.md`:
```markdown
# EasyCRM — Tenant Isolation Demo (SYNTHETIC data)

All data below is synthetic. GSTINs, if shown, are checksum-valid but fabricated.

## Run
    cd backend
    docker compose up -d
    SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun

Two synthetic tenants are seeded: `alpha-traders` (state 27) and
`bravo-distributors` (state 29).

## Proof 1 — cross-tenant read returns 404 (not 403)
1. Mint an OWNER token for Alpha (use the login endpoint once P0-auth lands; for
   now, a test token via `JwtService`).
2. Find a Bravo record id (query as Bravo, or read the seeder logs).
3. `curl -H "Authorization: Bearer <ALPHA_TOKEN>" \
        localhost:8080/api/v1/demo-records/<BRAVO_RECORD_ID>`
   → **HTTP 404** — Alpha cannot even confirm the record exists.

## Proof 2 — raw SQL with no tenant context returns zero rows
Connect as the NON-OWNER app role and query without setting the tenant GUC:

    psql "postgresql://easycrm_app:easycrm_app@localhost:5432/easycrm" \
      -c "SELECT count(*) FROM demo_record;"
    -- count = 0  (Row-Level Security blocks the non-owner role)

Now set the tenant and see rows appear:

    psql "postgresql://easycrm_app:easycrm_app@localhost:5432/easycrm" -c "
      BEGIN;
      SELECT set_config('app.current_tenant', '<ALPHA_TENANT_ID>', true);
      SELECT count(*) FROM demo_record;
      COMMIT;"
    -- count = 2  (only Alpha's rows)

Proof 2 is the important half: it shows the DATABASE enforces isolation,
independent of any application code.
```

- [ ] **Step 7: Full suite green**

Run: `cd backend && ./gradlew test`
Expected: PASS (all tasks).

- [ ] **Step 8: Commit + update challenge log if warranted**

```bash
git add backend/ docs/ && git commit -m "feat: two-tenant synthetic seed + isolation demo walkthrough"
```

---

## Self-Review

**Spec coverage (spec §3 four layers):**
- Layer 1 (JWT-only resolution) → Tasks 5, 9, 10 ✅
- Layer 2 (Hibernate `@TenantId`) → Tasks 7, 8 ✅
- Layer 3 (Postgres RLS + set_config in tx manager, non-owner role) → Tasks 3, 11 ✅
- Layer 4 (ArchUnit) → Task 15 ✅
- Async context propagation (spec §3) → Task 13 ✅
- 404-not-403 (spec §3/§6) → Tasks 12, 14 ✅
- Cross-tenant + RLS zero-rows tests (spec §3) → Tasks 11, 14 ✅
- Two-tenant synthetic demo (spec §7 asset #1) → Task 16 ✅
- BaseEntity/UUIDv7/optimistic locking (spec §2) → Task 4 ✅
- Tenant registry (spec §2 P0) → Task 6 ✅

**Deferred to the P0-auth plan (next):** signup/provisioning, login endpoint, password hashing (Argon2id), users/roles/invitations, refresh tokens, audit_log, record-level visibility layer, rate limiting. These are noted in spec §2/§3 and are NOT in this isolation-foundation scope by design.

**Placeholder scan:** No TBD/TODO. The only soft spot is the `DemoSeeder` transactional note (Task 16 Step 3) — it documents the fallback explicitly rather than leaving it vague, and the behaviour is pinned by `DemoSeederTest`.

**Type consistency:** `TenantContext.TenantPrincipal(UUID, UUID, String)` used identically in Tasks 5, 8, 9, 11, 12, 13, 14, 16. `TenantIdentifierResolver.NO_TENANT`, `TenantAwareTransactionManager(EntityManagerFactory)`, `JwtService.mint/parse`, `NotFoundException` all referenced consistently. `set_config('app.current_tenant', …, true)` matches the V4 policy's `current_setting('app.current_tenant', true)`.

---

## Open Items to carry into the P0-auth plan

- springdoc-openapi Spring Boot 4 compatibility check (needed in P1 for OpenAPI→TS, not before).
- Argon2id parameters + JWT signing choice (HS256 now; revisit RS256 + rotation).
- Whether the seed path needs per-tenant transaction splitting under RLS (spike during auth work; `DemoSeederTest` currently pins the direct-call behaviour).
