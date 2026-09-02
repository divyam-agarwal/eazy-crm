# OpenAPI Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a committed, machine-checkable OpenAPI 3 document for this backend's 74 endpoints, guarded so it cannot silently fall behind the code.

**Architecture:** springdoc generates the document from the existing controllers and types. A committed YAML snapshot at `docs/api/openapi.yaml` is the artefact; one JUnit test in two modes both generates it and guards it against drift, so the guard cannot check something different from what the generator emits. Two supporting changes make the generated document actually describe this API: the error envelope becomes typed (it is a raw `Map` today, which springdoc renders as a property-less object), and `Pageable` parameters are annotated so they render as the query parameters the server really accepts.

**Tech Stack:** Java 25, Spring Boot 4.1.0, Gradle (Kotlin DSL, version catalog), springdoc-openapi 3.1.0, JUnit 5, Testcontainers Postgres, GitHub Actions, oasdiff.

**Spec:** `docs/superpowers/specs/2026-09-01-openapi-contract-design.md`

## Global Constraints

- **Branch:** `openapi-contract`, off `main` at `e9d694e`. The spec is already committed there as `4a1848b`.
- **Baseline:** 519 tests, 0 failures, 0 errors. The baseline command is `./gradlew clean check` (test + `spotlessCheck` + `spotbugsMain` + `jacocoTestCoverageVerification`, both projects) — **not** `clean test`.
- **Counting tests:** Gradle prints no total for a multi-project build. Count both projects:
  ```bash
  find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
  ```
  A root-only `find . -path './build/...'` reports 496 and is wrong. This has already tripped an implementer.
- **Filtered test runs must be project-qualified:** `./gradlew :test --tests '<filter>'`. An unqualified `--tests` applies the filter to `:platform:platform-primitives` too and fails there for having no match.
- **springdoc version: exactly `3.1.0`.** The 3.x line is the Spring Boot 4 line (its POM parent is `spring-boot-starter-parent` 4.1.0). The 2.x line is Boot 3 and will not work. Do not "upgrade" to 2.9.0 because the number looks larger in the 2.x series.
- **Versions live in `backend/gradle/libs.versions.toml` only**, with a comment recording what was verified and on what date, matching every other entry in that file.
- **No change to endpoint behaviour, routing, status codes or payloads.** The error envelope is a representation change. If this slice changes what the server does, it has failed.
- **`docs/api/openapi.yaml` is generated output.** Never hand-edited, never formatted by anything. Spotless's `format("yaml")` targets `src/**/*.yml` only, so it does not reach this file — verify that stays true, and do not add a target that would.
- **Commits:** author as `divyam <divyam.0444@gmail.com>` via plain `git commit`. Never add a `Co-Authored-By: Claude` trailer and never mention Claude or AI in a commit message.

---

## File Structure

**Created:**

| Path | Responsibility |
|---|---|
| `backend/src/main/java/com/easycrm/platform/openapi/OpenApiConfig.java` | The single `OpenAPI` bean: title, version, security scheme. No request-path logic. |
| `backend/src/main/java/com/easycrm/platform/error/ApiError.java` | The typed inner error object: `code`, `message`, optional `fields`. |
| `backend/src/main/java/com/easycrm/platform/error/ApiErrorResponse.java` | The typed envelope: `{"error": {...}}`. |
| `backend/src/main/java/com/easycrm/platform/openapi/DevApiDocsSecurityConfig.java` | `@Profile("dev")` filter chain permitting the springdoc routes. Additive; deleting it restores today's behaviour. |
| `backend/src/test/java/com/easycrm/platform/error/ApiErrorWireFormatTest.java` | Characterization test: the exact JSON each handler produces. |
| `backend/src/test/java/com/easycrm/platform/openapi/OpenApiConfigTest.java` | Unit test of the `OpenAPI` bean's contents. |
| `backend/src/test/java/com/easycrm/platform/openapi/OpenApiSnapshotTest.java` | Generate-and-compare (default) or generate-and-write (`-Dopenapi.write=true`). |
| `backend/src/test/java/com/easycrm/platform/openapi/ApiDocsExposureTest.java` | Asserts the non-dev posture: the springdoc routes are not reachable. |
| `docs/api/openapi.yaml` | The committed contract snapshot. Generated output. |

**Modified:**

| Path | Change |
|---|---|
| `backend/gradle/libs.versions.toml` | `springdoc` version + two library coordinates. |
| `backend/build.gradle.kts` | Two dependencies (one `implementation`, one `developmentOnly`), `springBoot { buildInfo() }`, the `updateOpenApiSnapshot` task, the snapshot path system property on `test`. |
| `backend/src/main/java/com/easycrm/platform/error/ApiExceptionHandler.java` | All seven handlers return `ResponseEntity<ApiErrorResponse>`; each distinct status gains `@ApiResponse`. |
| `backend/src/test/java/com/easycrm/platform/error/ApiExceptionHandlerTest.java` | Type-level update only; assertions stay semantically identical. |
| 8 controllers | `@ParameterObject` on the `Pageable` parameter. |
| `backend/src/main/java/com/easycrm/demo/DemoRecordController.java` | `@Hidden`. |
| `backend/src/main/resources/application.yml`, `application-dev.yml` | springdoc ordering (Task 4) + `enabled` flags, off by default, on in `dev` (Task 5). |
| `.github/workflows/ci.yml` | `fetch-depth: 2` and the oasdiff step. |
| `docs/superpowers/HANDOFF.md`, `annotations-reference.md`, `engineering-challenges.md` | Wrap-up. |

**Deliberately not modified:** `backend/src/main/java/com/easycrm/platform/security/SecurityConfig.java`. Its `.anyRequest().denyAll()` stays the production answer for the springdoc paths; the dev chain is a separate, profile-scoped bean.

---

### Task 1: springdoc dependency and the OpenAPI bean

**Files:**
- Modify: `backend/gradle/libs.versions.toml`
- Modify: `backend/build.gradle.kts`
- Create: `backend/src/main/java/com/easycrm/platform/openapi/OpenApiConfig.java`
- Test: `backend/src/test/java/com/easycrm/platform/openapi/OpenApiConfigTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `OpenApiConfig(BuildProperties)` with `public OpenAPI customOpenApi()`. Task 4 relies on springdoc being on the `implementation` classpath and on the security scheme being named exactly `bearer-jwt`.

- [ ] **Step 1: Add the catalog entries**

In `backend/gradle/libs.versions.toml`, under `[versions]`, after the `findsecbugs` entry:

```toml
# --- API contract, added by the openapi-contract slice ---
# Verified against repo1.maven.org/maven2 on 2026-09-01 (latest/release: 3.1.0, published
# 2026-08-01). The 3.x line is the Spring Boot 4 line -- its POM parent is
# spring-boot-starter-parent 4.1.0, matching springBoot above. The 2.x line (latest 2.9.0)
# is the Boot 3 line and will NOT work here; every pre-2026 tutorial names it.
springdoc = "3.1.0"
```

Under `[libraries]`:

```toml
springdoc-webmvc-api = { module = "org.springdoc:springdoc-openapi-starter-webmvc-api", version.ref = "springdoc" }
springdoc-webmvc-ui = { module = "org.springdoc:springdoc-openapi-starter-webmvc-ui", version.ref = "springdoc" }
```

- [ ] **Step 2: Add the dependencies, split across two configurations**

In `backend/build.gradle.kts`, in the `dependencies { }` block, after the `caffeine` line:

```kotlin
    // OpenAPI generation. The generator ships; the browsable UI does not.
    // springdoc 3.x is the Spring Boot 4 line -- see the catalog comment. 2.x is Boot 3.
    implementation(libs.springdoc.webmvc.api)
    // developmentOnly is Spring Boot's own configuration: on the bootRun classpath,
    // excluded from bootJar. The swagger-ui webjar therefore never reaches a production
    // artefact even if someone later flips springdoc.swagger-ui.enabled by mistake --
    // structural absence rather than a configured one.
    developmentOnly(libs.springdoc.webmvc.ui)
```

Then, after the `java { toolchain ... }` block, add:

```kotlin
// Generates META-INF/build-info.properties from the Gradle project version, exposed at
// runtime as the actuator's BuildProperties bean. OpenApiConfig reads the API document's
// info.version from it, so the Gradle version is the single source of truth -- a literal in
// application.yml would be a second copy to keep in sync by hand, which is exactly what the
// version catalog's comments exist to prevent.
springBoot { buildInfo() }
```

- [ ] **Step 3: Confirm `springdoc.writer-with-order-by-keys` actually exists on 3.1.0**

The whole snapshot design (Task 4) rests on deterministic output ordering. An ordering flag that silently does nothing produces an *intermittently* failing build, which is worse than an obviously broken one — the spec calls this out as the same untested-assumption shape as challenge #33. Confirm the property is real before depending on it.

```bash
cd /Users/divyam/Documents/easy-crm/backend
./gradlew :dependencies --configuration runtimeClasspath > /dev/null
JAR=$(find ~/.gradle/caches/modules-2 -name 'springdoc-openapi-starter-common-3.1.0.jar' | head -1)
echo "jar: $JAR"
unzip -p "$JAR" org/springdoc/core/properties/SpringDocConfigProperties.class | strings | grep -i 'writerWithOrderByKeys'
```

Expected: at least one match on `writerWithOrderByKeys`. If there is **no** match, stop and record it — the fallback is a normalising comparison in Task 4 (parse both documents and compare the parsed trees), at the cost of the snapshot no longer being byte-for-byte what springdoc emits. Do not proceed on the assumption that it works.

- [ ] **Step 4: Write the failing test for the OpenAPI bean**

Create `backend/src/test/java/com/easycrm/platform/openapi/OpenApiConfigTest.java`. This is a plain unit test — the bean method is pure, so it needs no Spring context, matching `ApiExceptionHandlerTest`'s style.

```java
package com.easycrm.platform.openapi;

import static org.junit.jupiter.api.Assertions.*;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;

class OpenApiConfigTest {

    private static BuildProperties buildProperties(String version) {
        Properties p = new Properties();
        p.setProperty("version", version);
        return new BuildProperties(p);
    }

    private final OpenAPI api =
            new OpenApiConfig(buildProperties("0.0.1-SNAPSHOT")).customOpenApi();

    @Test
    void carriesTitleAndTheInjectedProjectVersion() {
        assertEquals("EasyCRM API", api.getInfo().getTitle());
        // The version comes from the Gradle project version via BuildProperties, not from a
        // literal and deliberately not from a date: a date would churn the snapshot on every
        // regeneration and make the drift guard in OpenApiSnapshotTest fire on the calendar
        // rather than on a real change.
        assertEquals("0.0.1-SNAPSHOT", api.getInfo().getVersion());
    }

    @Test
    void declaresABearerJwtSecurityScheme() {
        SecurityScheme scheme = api.getComponents().getSecuritySchemes().get("bearer-jwt");
        assertNotNull(scheme, "scheme must be named exactly bearer-jwt; the snapshot depends on it");
        assertEquals(SecurityScheme.Type.HTTP, scheme.getType());
        assertEquals("bearer", scheme.getScheme());
        assertEquals("JWT", scheme.getBearerFormat());
    }

    @Test
    void appliesTheSchemeGlobally() {
        // Most routes need a JWT; the handful that do not are the documented exceptions
        // (auth, public share, invitation accept/preview). A global requirement is the
        // smaller, more honest default than annotating 74 endpoints individually.
        assertEquals(1, api.getSecurity().size());
        assertTrue(api.getSecurity().get(0).containsKey("bearer-jwt"));
    }
}
```

- [ ] **Step 5: Run the test to verify it fails**

Run: `cd /Users/divyam/Documents/easy-crm/backend && ./gradlew :test --tests '*OpenApiConfigTest'`
Expected: FAIL — compilation error, `OpenApiConfig` does not exist.

- [ ] **Step 6: Write `OpenApiConfig`**

Create `backend/src/main/java/com/easycrm/platform/openapi/OpenApiConfig.java`:

```java
package com.easycrm.platform.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single source of the generated document's non-derived metadata. Everything else in the
 * spec — paths, schemas, parameters — is read off the controllers and DTOs by springdoc; this
 * bean supplies only what cannot be inferred.
 */
@Configuration
public class OpenApiConfig {

    private final String version;

    /**
     * The version is the Gradle project version, reaching us through the build-info file that
     * {@code springBoot { buildInfo() }} generates. One source of truth: a literal in
     * application.yml would be a second copy of the same number to keep in sync by hand.
     */
    public OpenApiConfig(BuildProperties buildProperties) {
        this.version = buildProperties.getVersion();
    }

    @Bean
    public OpenAPI customOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("EasyCRM API")
                        .version(version)
                        .description(
                                """
                                Multi-tenant CRM for Indian distributors, traders and small manufacturers. \
                                Scope stops at the Order: no invoicing, stock or ledger.

                                Every route under /api/** requires a bearer JWT except the auth routes, \
                                the invitation accept/preview pair, and GET /public/q/{token}. \
                                Money is carried as a JSON string, never a number. Errors share one \
                                envelope: {"error":{"code","message","fields"}}.\
                                """))
                .components(new Components()
                        .addSecuritySchemes(
                                "bearer-jwt",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
    }
}
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `cd /Users/divyam/Documents/easy-crm/backend && ./gradlew :test --tests '*OpenApiConfigTest'`
Expected: PASS, 3 tests.

- [ ] **Step 8: Verify the UI starter is genuinely absent from the production artefact**

This is the point of the two-configuration split, so prove it rather than assume it:

```bash
cd /Users/divyam/Documents/easy-crm/backend
./gradlew bootJar
unzip -l build/libs/easycrm-backend-0.0.1-SNAPSHOT.jar | grep -ci 'swagger-ui'
unzip -l build/libs/easycrm-backend-0.0.1-SNAPSHOT.jar | grep -c 'springdoc-openapi-starter-webmvc-api'
```

Expected: `0` for swagger-ui, `1` for the `-api` starter.

If Spring's auto-configuration later fails to start because the UI starter is missing from the runtime classpath (this surfaces in Task 5 Step 7, not here), the fallback is to move `springdoc-webmvc-ui` to `implementation` and rely on the two `enabled`-flag layers instead — and to write down the reason, because it weakens the guarantee.

- [ ] **Step 9: Run the full check**

Run: `cd /Users/divyam/Documents/easy-crm/backend && ./gradlew clean check`
Expected: PASS. Test count 519 + 3 = **522**. Coverage floors (root LINE 0.92 / BRANCH 0.81) still met — `OpenApiConfig` is fully exercised by the new test.

- [ ] **Step 10: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/gradle/libs.versions.toml backend/build.gradle.kts \
        backend/src/main/java/com/easycrm/platform/openapi/OpenApiConfig.java \
        backend/src/test/java/com/easycrm/platform/openapi/OpenApiConfigTest.java
git commit -m "feat: add springdoc 3.1.0 and the OpenAPI metadata bean

springdoc 3.x is the Spring Boot 4 line; 2.x is Boot 3 and does not work
here. The generator goes on implementation, the swagger-ui starter on
developmentOnly so the webjar never reaches bootJar -- verified by
inspecting the jar rather than assumed.

info.version comes from the Gradle project version via buildInfo() --
one source of truth rather than a literal in application.yml -- and
deliberately not from a date, so the snapshot guard added later fires on
real changes and not on the calendar."
```

---

### Task 2: The typed error envelope

This is the highest-consequence task in the slice: it is the only change to existing application logic, and seven exception paths plus a large number of MockMvc assertions run through it. **The acceptance bar is that the JSON on the wire does not change.**

**Files:**
- Create: `backend/src/main/java/com/easycrm/platform/error/ApiError.java`
- Create: `backend/src/main/java/com/easycrm/platform/error/ApiErrorResponse.java`
- Create: `backend/src/test/java/com/easycrm/platform/error/ApiErrorWireFormatTest.java`
- Modify: `backend/src/main/java/com/easycrm/platform/error/ApiExceptionHandler.java`
- Modify: `backend/src/test/java/com/easycrm/platform/error/ApiExceptionHandlerTest.java`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `public record ApiErrorResponse(ApiError error)` and `public record ApiError(String code, String message, Map<String, Object> fields)`, both in `com.easycrm.platform.error`. Task 4's snapshot will contain schemas named `ApiErrorResponse` and `ApiError`.

- [ ] **Step 1: Write the characterization test — BEFORE changing anything**

This is a refactoring task, so the test is written first and must pass **both before and after** the change. That is what makes it a proof of behaviour-preservation rather than a description of the new code. It is written against the serialized bytes and against `ResponseEntity<?>`, so it compiles and passes against both the old `Map` return type and the new record type.

Create `backend/src/test/java/com/easycrm/platform/error/ApiErrorWireFormatTest.java`:

```java
package com.easycrm.platform.error;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * The error envelope is the first thing any client has to handle, and the typed-record
 * conversion this slice makes is a representation change that must not reach the wire.
 * These assertions are deliberately written against the serialized bytes rather than against
 * the Java type, so they hold identically before and after that conversion.
 *
 * <p>If one of these fails after the conversion, the conversion changed behaviour. Fix the
 * code, never the expectation.
 */
class ApiErrorWireFormatTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private final ObjectMapper mapper = new ObjectMapper();

    private String json(ResponseEntity<?> resp) throws Exception {
        return mapper.writeValueAsString(resp.getBody());
    }

    @Test
    void errorWithoutFieldsOmitsTheFieldsKeyEntirely() throws Exception {
        // The case a naive record conversion silently breaks: a record component serializes
        // as "fields":null by default, which is a different document from one with no fields
        // key at all. @JsonInclude(NON_NULL) is what preserves this.
        String body = json(handler.notFound(new NotFoundException("quotation not found")));

        assertEquals("{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"quotation not found\"}}", body);
        assertFalse(body.contains("fields"), "absent fields must not serialize as null");
    }

    @Test
    void errorWithFieldsCarriesThemNested() throws Exception {
        String body = json(handler.validation(new ValidationException("gstin", "GSTIN checksum is invalid")));

        assertEquals(
                "{\"error\":{\"code\":\"VALIDATION_FAILED\",\"message\":\"request is invalid\","
                        + "\"fields\":{\"gstin\":\"GSTIN checksum is invalid\"}}}",
                body);
    }

    @Test
    void everyHandlerProducesTheSameEnvelopeShape() throws Exception {
        // All the direct-call paths, so none of them can drift alone.
        assertTrue(json(handler.unauthorized(new UnauthorizedException("bad credentials")))
                .startsWith("{\"error\":{\"code\":\"UNAUTHORIZED\""));
        assertTrue(json(handler.forbidden(new ForbiddenException("owner only")))
                .startsWith("{\"error\":{\"code\":\"FORBIDDEN\""));
        assertTrue(json(handler.conflict(new ConflictException("slug taken")))
                .startsWith("{\"error\":{\"code\":\"CONFLICT\""));
        assertTrue(json(handler.dataIntegrity(new DataIntegrityViolationException("dup")))
                .startsWith("{\"error\":{\"code\":\"CONFLICT\""));
        assertTrue(json(handler.optimisticLock(
                        new ObjectOptimisticLockingFailureException(Object.class, UUID.randomUUID())))
                .startsWith("{\"error\":{\"code\":\"CONFLICT\""));
    }
}
```

Note: `NotFoundException`, `UnauthorizedException`, `ForbiddenException`, `ConflictException` and `ValidationException` all already exist in this package; `ValidationException` has a `(String field, String message)` constructor — confirm the exact signatures before writing, and adjust the construction only (never the assertions) if one differs.

- [ ] **Step 2: Run it against the UNCHANGED code — it must PASS**

Run: `cd /Users/divyam/Documents/easy-crm/backend && ./gradlew :test --tests '*ApiErrorWireFormatTest'`
Expected: **PASS**, 3 tests, against today's `Map<String, Object>` implementation.

This step is the point of the whole task. A characterization test that fails here is not describing current behaviour, and every later comparison against it is meaningless. If it fails, the expected strings are wrong — correct them to match what the code actually emits **today**, then continue.

One thing that could legitimately differ: **JSON object key order.** The current code builds a `HashMap`, whose iteration order is an implementation detail, while a record serializes in declaration order. Analysis of the three keys says both give `code, message, fields`, but if this step shows otherwise, fix the expected string to today's actual order now. If Step 6 then changes that order, it is acceptable: record it in the challenge log and relax these two assertions to `assertEquals(mapper.readTree(expected), mapper.readTree(body))`. JSON objects are unordered per RFC 8259, no client can depend on the order, and every `jsonPath("$.error.…")` assertion in the suite is order-insensitive. **Do not relax them pre-emptively** — only if the order actually moves.

- [ ] **Step 3: Commit the characterization test on its own**

Committing it separately is what makes the next commit's diff readable as "behaviour unchanged": this test is in the history, passing, against the old code.

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/src/test/java/com/easycrm/platform/error/ApiErrorWireFormatTest.java
git commit -m "test: pin the error envelope's exact wire format

Written against the serialized bytes rather than the Java type, so it
holds across the typed-record conversion that follows. Passes here
against the existing Map-based handler; if it fails after that
conversion, the conversion changed behaviour."
```

- [ ] **Step 4: Write the two records**

Create `backend/src/main/java/com/easycrm/platform/error/ApiError.java`:

```java
package com.easycrm.platform.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * The inner half of this API's single error envelope.
 *
 * <p>{@code fields} stays a free-form map on purpose: its keys are dynamic field names taken
 * from a binding result or from {@link ValidationException#getFields()}, so there is no closed
 * schema to write, and the generated OpenAPI document describes it as a free-form object —
 * honest rather than lazy.
 *
 * <p>{@code @JsonInclude} sits on the type, not on the {@code fields} component: {@code code}
 * and {@code message} are never null, so type-level NON_NULL is equivalent, is one annotation
 * instead of one per component, and does not rest on record-component annotation propagation
 * behaving as assumed. Without it, an error with no field detail would serialize
 * {@code "fields":null} — a different document from today's, which omits the key entirely.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, Map<String, Object> fields) {}
```

Create `backend/src/main/java/com/easycrm/platform/error/ApiErrorResponse.java`:

```java
package com.easycrm.platform.error;

/**
 * The envelope every error response in this API is wrapped in: {@code {"error": {...}}}.
 * Exists as a named type so the generated OpenAPI document can reference one schema for every
 * 4xx instead of the property-less {@code object} a raw {@code Map} produced.
 */
public record ApiErrorResponse(ApiError error) {}
```

- [ ] **Step 5: Convert the handler**

Rewrite `backend/src/main/java/com/easycrm/platform/error/ApiExceptionHandler.java`. Every handler's status, code, message and fields stay exactly as they are — only the type changes, plus the `@ApiResponse` annotations that make the generated document describe the error shape.

```java
package com.easycrm.platform.error;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.HashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    @ApiResponse(
            responseCode = "404",
            description = "the resource does not exist, or belongs to another tenant",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<ApiErrorResponse> notFound(NotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), null);
    }

    @ExceptionHandler(UnauthorizedException.class)
    @ApiResponse(
            responseCode = "401",
            description = "missing, expired or invalid bearer token",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<ApiErrorResponse> unauthorized(UnauthorizedException ex) {
        return body(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", ex.getMessage(), null);
    }

    @ExceptionHandler(ForbiddenException.class)
    @ApiResponse(
            responseCode = "403",
            description = "authenticated, but the role does not permit this operation",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<ApiErrorResponse> forbidden(ForbiddenException ex) {
        return body(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage(), null);
    }

    @ExceptionHandler(ConflictException.class)
    @ApiResponse(
            responseCode = "409",
            description = "the request conflicts with existing data",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<ApiErrorResponse> conflict(ConflictException ex) {
        return body(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage(), null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> dataIntegrity(DataIntegrityViolationException ex) {
        // Backstop for unique/constraint violations that slip past app-level pre-checks
        // (update() paths, concurrent create() races). Data stays correct; the client gets 409.
        return body(HttpStatus.CONFLICT, "CONFLICT", "the request conflicts with existing data", null);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiErrorResponse> optimisticLock(OptimisticLockingFailureException ex) {
        // A concurrent @Version write lost the race. Data integrity is intact (exactly one writer
        // wins); the loser gets 409 instead of a raw 500. Sibling of the DataIntegrityViolation
        // backstop above — ObjectOptimisticLockingFailureException does NOT extend
        // DataIntegrityViolationException, so it needs its own handler.
        return body(
                HttpStatus.CONFLICT,
                "CONFLICT",
                "the request could not be completed due to a concurrent update; please retry",
                null);
    }

    @ExceptionHandler(ValidationException.class)
    @ApiResponse(
            responseCode = "422",
            description = "semantically invalid request; `fields` names the offending inputs",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<ApiErrorResponse> validation(ValidationException ex) {
        Map<String, Object> fields = new HashMap<>(ex.getFields());
        return body(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "request is invalid", fields);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ApiResponse(
            responseCode = "400",
            description = "bean-validation failure on the request body",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<ApiErrorResponse> invalid(MethodArgumentNotValidException ex) {
        Map<String, Object> fields = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> fields.put(fe.getField(), fe.getDefaultMessage()));
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "request is invalid", fields);
    }

    private ResponseEntity<ApiErrorResponse> body(
            HttpStatus status, String code, String message, Map<String, Object> fields) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(new ApiError(code, message, fields)));
    }
}
```

The two extra 409 handlers deliberately carry no `@ApiResponse`: `conflict` already documents 409 with the same schema, and two `@ApiResponse` annotations for one status code on one advice is a duplicate springdoc resolves arbitrarily. One per distinct status code.

- [ ] **Step 6: Run the characterization test — it must STILL pass**

Run: `cd /Users/divyam/Documents/easy-crm/backend && ./gradlew :test --tests '*ApiErrorWireFormatTest'`
Expected: **PASS**, unchanged, 3 tests.

If it fails on anything other than key order, the conversion changed behaviour — fix `ApiError`/`ApiExceptionHandler`, not the test. For key order specifically, apply the decision rule in Step 2.

- [ ] **Step 7: Update `ApiExceptionHandlerTest` for the type change**

This existing test destructures the returned `Map` directly, so it cannot compile against the new type. Its **assertions stay semantically identical** — same statuses, same codes, same field message. This is the only existing test that changes, and only because it asserts against the Java type rather than against the wire.

Replace `backend/src/test/java/com/easycrm/platform/error/ApiExceptionHandlerTest.java` with:

```java
package com.easycrm.platform.error;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void validationExceptionMapsTo422WithFields() {
        ResponseEntity<ApiErrorResponse> resp =
                handler.validation(new ValidationException("gstin", "GSTIN checksum is invalid"));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode());
        ApiError error = resp.getBody().error();
        assertEquals("VALIDATION_FAILED", error.code());
        assertEquals("GSTIN checksum is invalid", error.fields().get("gstin"));
    }

    @Test
    void optimisticLockMapsTo409() {
        ResponseEntity<ApiErrorResponse> resp =
                handler.optimisticLock(new ObjectOptimisticLockingFailureException(Object.class, UUID.randomUUID()));

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertEquals("CONFLICT", resp.getBody().error().code());
    }
}
```

The `@SuppressWarnings("unchecked")` annotations are gone because the unchecked casts they covered are gone — that is the readability half of this change.

- [ ] **Step 8: Run the FULL suite — the real proof**

Run: `cd /Users/divyam/Documents/easy-crm/backend && ./gradlew clean check`
Expected: PASS, **522 + 3 = 525** tests.

Every `jsonPath("$.error.code")`, `$.error.message` and `$.error.fields.*` assertion across the suite — `EnquiryCreateTest`, `PublicShareTest`, `QuotationControllerTest`, `OrderTransitionTest`, `FollowUpTransitionEndpointTest`, `ProductControllerTest`, `ActivityEndpointTest`, `EnquiryVisibilityTest` and the rest — runs through the converted handler. **No file outside `platform/error/` may be edited to make this pass.** If one of those assertions fails, the conversion is wrong.

- [ ] **Step 9: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/src/main/java/com/easycrm/platform/error/ \
        backend/src/test/java/com/easycrm/platform/error/ApiExceptionHandlerTest.java
git commit -m "refactor: give the error envelope a type so it can be documented

ApiExceptionHandler returned ResponseEntity<Map<String, Object>> from all
seven handlers. springdoc reads that as a property-less object, so the
generated spec would have described every success response precisely and
every error as 'some object' -- on the half a client has to handle first.

Two records replace it. Status, code, message and fields are untouched;
@JsonInclude(NON_NULL) preserves omitting 'fields' when there is no field
detail, which a plain record would have emitted as null. The wire-format
test added in the previous commit passes unchanged against both
implementations, and no assertion outside platform/error was edited."
```

---

### Task 3: Structural annotations

**Files:**
- Modify: `backend/src/main/java/com/easycrm/sales/web/EnquiryController.java`, `QuotationController.java`, `ActivityController.java`, `FollowUpController.java`, `OrderController.java`
- Modify: `backend/src/main/java/com/easycrm/crm/web/CustomerController.java`
- Modify: `backend/src/main/java/com/easycrm/catalog/web/PriceListController.java`, `ProductController.java`
- Modify: `backend/src/main/java/com/easycrm/demo/DemoRecordController.java`

**Interfaces:**
- Consumes: springdoc on the classpath (Task 1).
- Produces: no Java API. Task 4's snapshot depends on these: without them it would record the paging parameters wrongly and would include the demo fixture.

- [ ] **Step 1: Annotate the 8 `Pageable` parameters**

Add `import org.springdoc.core.annotations.ParameterObject;` to each file and change `Pageable pageable` to `@ParameterObject Pageable pageable`. Without this, springdoc renders `Pageable` as a single nested object rather than the `page`/`size`/`sort` query parameters the server actually accepts — worse than omitting it, because it documents a request the server will not honour.

Sites (line numbers are from `main` at `e9d694e` and are a locator, not a guarantee — match on `Pageable pageable`):

- `sales/web/EnquiryController.java:73`
- `sales/web/QuotationController.java:62`
- `sales/web/ActivityController.java:46`
- `sales/web/FollowUpController.java:56`
- `sales/web/OrderController.java:53`
- `crm/web/CustomerController.java:35`
- `catalog/web/PriceListController.java:35`
- `catalog/web/ProductController.java:36`

Confirm all eight were caught:

```bash
cd /Users/divyam/Documents/easy-crm/backend
grep -rc '@ParameterObject Pageable' src/main/java --include='*Controller.java' | grep -v ':0'
```
Expected: 8 files, each with count 1.

- [ ] **Step 2: Hide the demo fixture**

In `backend/src/main/java/com/easycrm/demo/DemoRecordController.java`, add `import io.swagger.v3.oas.annotations.Hidden;` and annotate the class:

```java
// Excluded from the generated OpenAPI document on purpose. This controller is the P0
// tenant-isolation demonstration fixture -- it exists to prove @TenantId + RLS return 404
// rather than 403 for another tenant's row -- and it is not product surface a client should
// ever be written against. Hiding it does NOT remove the route: GET /api/v1/demo-records/{id}
// remains live and authenticated in every profile. If it should not exist in production, that
// is a separate decision and a separate slice.
@Hidden
@RestController
@RequestMapping("/api/v1/demo-records")
public class DemoRecordController {
```

- [ ] **Step 3: Run the full check**

Run: `cd /Users/divyam/Documents/easy-crm/backend && ./gradlew clean check`
Expected: PASS, **525** tests, unchanged. These are documentation-only annotations; `DemoRecordControllerTest` and `CrossTenantIsolationIntegrationTest` must still pass, which is what proves `@Hidden` did not remove the route.

- [ ] **Step 4: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/src/main/java/com/easycrm/
git commit -m "docs: annotate Pageable params and hide the demo fixture

@ParameterObject on the eight list endpoints so page/size/sort render as
the query parameters the server actually accepts; without it springdoc
documents a single nested object, which is a request the server will not
honour.

@Hidden on DemoRecordController: it is the P0 tenant-isolation
demonstration fixture, not product surface. The route stays live -- this
only keeps it out of the document a client gets written against."
```

---

### Task 4: The snapshot and the drift guard

**Files:**
- Modify: `backend/build.gradle.kts`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/java/com/easycrm/platform/openapi/OpenApiSnapshotTest.java`
- Create: `docs/api/openapi.yaml` (generated)

**Interfaces:**
- Consumes: `OpenApiConfig` (Task 1), the typed error schemas (Task 2), the annotations (Task 3).
- Produces: the Gradle task `updateOpenApiSnapshot`, and the system properties `openapi.snapshot` (absolute path to `docs/api/openapi.yaml`) and `openapi.write`, both read by the test. Task 6 depends on the file existing at that exact path.

- [ ] **Step 1: Wire the snapshot path and the regeneration task**

The Gradle root is `backend/`; the snapshot lives one directory up at `docs/api/openapi.yaml`. Do **not** resolve it from the test's working directory — inject the absolute path, so the test has no opinion about where it was launched from.

In `backend/build.gradle.kts`, replace the existing line `tasks.withType<Test> { useJUnitPlatform() }` with:

```kotlin
// The committed OpenAPI snapshot lives outside the Gradle project (the Gradle root is
// backend/, the snapshot is at <repo>/docs/api/openapi.yaml). The absolute path is injected
// rather than derived from the test's working directory, which is not something a test should
// have an opinion about.
val openApiSnapshot = layout.projectDirectory.file("../docs/api/openapi.yaml")

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("openapi.snapshot", openApiSnapshot.asFile.absolutePath)
}

// Regenerates docs/api/openapi.yaml by running the drift guard in write mode. Deliberately the
// SAME test, not a second generation path: a separate generator could disagree with the guard,
// and then neither would mean anything.
tasks.register<Test>("updateOpenApiSnapshot") {
    group = "documentation"
    description = "Regenerate docs/api/openapi.yaml from the current controllers."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    systemProperty("openapi.snapshot", openApiSnapshot.asFile.absolutePath)
    systemProperty("openapi.write", "true")
    filter { includeTestsMatching("com.easycrm.platform.openapi.OpenApiSnapshotTest") }
    outputs.upToDateWhen { false }
}
```

Registering it as its own `Test` task rather than mutating the `test` task keeps the two modes from ever being active at once, and keeps `check` unaffected.

- [ ] **Step 2: Enable deterministic ordering**

In `backend/src/main/resources/application.yml`, add a new top-level `springdoc:` block (Task 5 adds the `enabled` flags to this same block):

```yaml
springdoc:
  # Sorts the serialized document's keys. Without it springdoc's internal maps have no
  # guaranteed iteration order, so two runs over identical code can emit identical content in a
  # different order -- which would make OpenApiSnapshotTest fail intermittently for no real
  # reason, the worst available failure mode. Confirmed present on 3.1.0 in Task 1 Step 3.
  writer-with-order-by-keys: true
```

- [ ] **Step 3: Write the drift guard**

Create `backend/src/test/java/com/easycrm/platform/openapi/OpenApiSnapshotTest.java`:

```java
package com.easycrm.platform.openapi;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.easycrm.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The drift guard: the document springdoc generates from the current controllers must equal the
 * committed snapshot at docs/api/openapi.yaml. Adding an endpoint, renaming a field, changing a
 * status code or adding a query parameter all fail {@code ./gradlew clean check} until the
 * snapshot is regenerated and committed alongside the change — which is the whole difference
 * between having a Swagger page and having a contract.
 *
 * <p>Run {@code ./gradlew updateOpenApiSnapshot} to rewrite the snapshot instead of asserting
 * against it. That is deliberately this same test in a second mode and not a second generator:
 * two generation paths could disagree, and then neither artefact would mean anything.
 *
 * <p>{@code addFilters = false} bypasses the security chain — SecurityConfig ends in
 * {@code denyAll()}, so the springdoc route is not reachable in a test otherwise. Nothing here
 * asserts anything about authorization; ApiDocsExposureTest and the dev-profile chain govern
 * real exposure.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "springdoc.api-docs.enabled=true")
class OpenApiSnapshotTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void generatedDocumentMatchesTheCommittedSnapshot() throws Exception {
        String generated = mvc.perform(get("/v3/api-docs.yaml"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertFalse(generated.isBlank(), "springdoc produced an empty document");
        assertTrue(generated.contains("EasyCRM API"), "the OpenApiConfig info block is missing");

        Path snapshot = Path.of(System.getProperty("openapi.snapshot"));

        if (Boolean.getBoolean("openapi.write")) {
            Files.createDirectories(snapshot.getParent());
            Files.writeString(snapshot, generated, StandardCharsets.UTF_8);
            System.out.println("openapi: wrote snapshot to " + snapshot);
            return;
        }

        assertTrue(Files.exists(snapshot), "missing snapshot: run ./gradlew updateOpenApiSnapshot");

        String committed = Files.readString(snapshot, StandardCharsets.UTF_8);
        if (!committed.equals(generated)) {
            // Dump the actual output so the difference can be diffed rather than guessed at from
            // a multi-thousand-line assertion message.
            Path actual = Path.of("build", "openapi-actual.yaml").toAbsolutePath();
            Files.createDirectories(actual.getParent());
            Files.writeString(actual, generated, StandardCharsets.UTF_8);
            fail("The API changed but docs/api/openapi.yaml did not.\n"
                    + "  Regenerate: ./gradlew updateOpenApiSnapshot\n"
                    + "  Then commit the snapshot with the change that caused it.\n"
                    + "  Generated output: " + actual + "\n"
                    + "  Diff: diff " + snapshot + " " + actual);
        }
    }
}
```

- [ ] **Step 4: Run it to verify it fails for the right reason**

Run: `cd /Users/divyam/Documents/easy-crm/backend && ./gradlew :test --tests '*OpenApiSnapshotTest'`
Expected: FAIL with `missing snapshot: run ./gradlew updateOpenApiSnapshot` — the snapshot does not exist yet.

- [ ] **Step 5: Generate the snapshot**

Run: `cd /Users/divyam/Documents/easy-crm/backend && ./gradlew updateOpenApiSnapshot`
Expected: PASS, and `docs/api/openapi.yaml` now exists.

Read it before committing it — it is the artefact this whole slice exists to produce:

```bash
cd /Users/divyam/Documents/easy-crm
wc -l docs/api/openapi.yaml
grep -c 'demo-records' docs/api/openapi.yaml     # expected: 0
grep -c 'ApiErrorResponse' docs/api/openapi.yaml # expected: > 0
grep -B2 -A6 'name: page' docs/api/openapi.yaml | head -30
```

Expected: **zero** `demo-records` (Task 3's `@Hidden`), `ApiErrorResponse` present (Task 2), and `page` appearing as an `in: query` parameter (Task 3's `@ParameterObject`). If any of those is wrong, the corresponding earlier task did not take effect — fix it there, not here.

- [ ] **Step 6: Prove the output is deterministic**

Run it twice and confirm the file does not move. If `writer-with-order-by-keys` is not doing what Step 2 assumes, this is where it shows up — **before** the build starts failing intermittently for someone else.

```bash
cd /Users/divyam/Documents/easy-crm/backend
shasum ../docs/api/openapi.yaml
./gradlew updateOpenApiSnapshot --rerun-tasks
shasum ../docs/api/openapi.yaml
```
Expected: identical checksums.

If they differ, take the fallback: parse both documents in the test and compare the parsed trees rather than the raw strings, and record why in the challenge log — the snapshot then stops being byte-for-byte what springdoc emits, which is a real cost worth writing down.

- [ ] **Step 7: Prove the guard can fail**

A gate that has never failed is indistinguishable from one that checks nothing — challenge #33 is the standing reason this step is mandatory, not optional.

Add a throwaway query parameter to `backend/src/main/java/com/easycrm/catalog/web/ProductController.java`'s `list(...)` method:

```java
            @RequestParam(required = false) String drift,
```

Then:
```bash
cd /Users/divyam/Documents/easy-crm/backend && ./gradlew :test --tests '*OpenApiSnapshotTest'
```
Expected: **FAIL**, with the message naming `./gradlew updateOpenApiSnapshot` and the path of the dumped output. Confirm `build/openapi-actual.yaml` exists and that `diff` against the snapshot shows the `drift` parameter.

Then revert and re-run:
```bash
cd /Users/divyam/Documents/easy-crm
git checkout -- backend/src/main/java/com/easycrm/catalog/web/ProductController.java
cd backend && ./gradlew :test --tests '*OpenApiSnapshotTest'
```
Expected: PASS.

- [ ] **Step 8: Confirm Spotless does not touch the generated file**

```bash
cd /Users/divyam/Documents/easy-crm/backend
./gradlew spotlessApply
cd /Users/divyam/Documents/easy-crm && git status --porcelain docs/api/openapi.yaml
```
Expected: **no output** — the file is unchanged. Spotless's `format("yaml")` targets `src/**/*.yml`, so `docs/api/openapi.yaml` is out of scope by construction. If it were ever formatted, the formatter and the generator would fight and the drift guard would never pass.

- [ ] **Step 9: Run the full check**

Run: `cd /Users/divyam/Documents/easy-crm/backend && ./gradlew clean check`
Expected: PASS, **525 + 1 = 526** tests.

Note the context cost: `OpenApiSnapshotTest`'s `@TestPropertySource` and `addFilters = false` give it a different context cache key from the shared `IntegrationTest` one, so it boots one additional Spring context. That is expected, and it is the price of not touching `SecurityConfig`.

- [ ] **Step 10: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/build.gradle.kts backend/src/main/resources/application.yml \
        backend/src/test/java/com/easycrm/platform/openapi/OpenApiSnapshotTest.java \
        docs/api/openapi.yaml
git commit -m "feat: commit the OpenAPI snapshot and guard it against drift

The generated document must equal docs/api/openapi.yaml or clean check
fails, naming the regeneration command and dumping the actual output for
diffing. Adding an endpoint or changing a field now forces the snapshot
to be regenerated and committed with the change that caused it.

The guard and the regeneration task are one test in two modes on purpose:
a second generation path could disagree with the guard, and then neither
would mean anything. Ordering is pinned with writer-with-order-by-keys
and the output proven byte-stable across two runs, because an
intermittently failing snapshot is worse than an obviously broken one.
Proven able to fail by adding a query parameter and watching it go red."
```

---

### Task 5: Dev-profile exposure

**Files:**
- Create: `backend/src/main/java/com/easycrm/platform/openapi/DevApiDocsSecurityConfig.java`
- Create: `backend/src/test/java/com/easycrm/platform/openapi/ApiDocsExposureTest.java`
- Modify: `backend/src/main/resources/application.yml`, `application-dev.yml`

**Interfaces:**
- Consumes: springdoc (Task 1).
- Produces: no Java API consumed by later tasks. `SecurityConfig` is not modified.

- [ ] **Step 1: Write the exposure test**

Create `backend/src/test/java/com/easycrm/platform/openapi/ApiDocsExposureTest.java`. This asserts the **default** (non-dev) posture, which is the one that matters — a mistake here is a permanently unauthenticated route in production.

```java
package com.easycrm.platform.openapi;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Outside the dev profile the springdoc routes must not be reachable. Two independent layers
 * produce that: springdoc's own enabled flags (false by default, so the routes are never
 * registered) and SecurityConfig's terminal denyAll(). The test suite does not run under the
 * dev profile, so this is the production posture.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiDocsExposureTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void apiDocsIsNotReachableOutsideDev() throws Exception {
        // 401 from SecurityConfig's HttpStatusEntryPoint, or 404 because springdoc never
        // registered the handler. Either is a correct "not exposed"; what must never happen is
        // a 200 carrying the document.
        int status = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getStatus();
        assertTrue(status == 401 || status == 404, "expected 401 or 404 outside dev, got " + status);
    }

    @Test
    void swaggerUiIsNotReachableOutsideDev() throws Exception {
        int status =
                mvc.perform(get("/swagger-ui/index.html")).andReturn().getResponse().getStatus();
        assertTrue(status == 401 || status == 404, "expected 401 or 404 outside dev, got " + status);
    }

    @Test
    void healthIsStillReachable() throws Exception {
        // Guards against the new dev filter chain accidentally taking precedence over the
        // existing one: /actuator/health is permitAll today and must stay that way.
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: Run it and record the baseline**

Run: `cd /Users/divyam/Documents/easy-crm/backend && ./gradlew :test --tests '*ApiDocsExposureTest'`
Expected: PASS. Record the actual status codes observed — they are the baseline the rest of this task must not change.

If either route returns **200**, springdoc's defaults are exposing the document today, which makes Step 3 load-bearing rather than belt-and-braces. Note it and continue.

- [ ] **Step 3: Turn the flags off by default, on in dev**

In `backend/src/main/resources/application.yml`, extend the `springdoc:` block added in Task 4:

```yaml
springdoc:
  # (writer-with-order-by-keys stays as added in Task 4)
  # Layer 1 of 2. Off by default, so outside dev the routes are never registered and there is
  # nothing for a security rule to have to deny. Layer 2 is SecurityConfig's terminal denyAll()
  # plus DevApiDocsSecurityConfig, which only exists under the dev profile.
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false
```

In `backend/src/main/resources/application-dev.yml`:

```yaml
springdoc:
  # Dev only. The browsable UI is the point of the dev profile here -- it is what the frontend
  # will be explored against. The swagger-ui webjar itself is on the developmentOnly
  # configuration (build.gradle.kts), so in production it is not merely disabled, it is absent.
  api-docs:
    enabled: true
  swagger-ui:
    enabled: true
```

- [ ] **Step 4: Add the dev-only filter chain**

Create `backend/src/main/java/com/easycrm/platform/openapi/DevApiDocsSecurityConfig.java`:

```java
package com.easycrm.platform.openapi;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Layer 2 of the dev-only exposure. SecurityConfig ends in {@code .anyRequest().denyAll()},
 * which is the right production answer for the springdoc paths; rather than punching a
 * conditional hole in it, this contributes a separate, higher-precedence chain that exists only
 * under the dev profile.
 *
 * <p>Two consequences worth keeping: SecurityConfig itself is untouched, so production
 * behaviour is exactly what it was; and deleting this file restores today's behaviour
 * completely, with no leftover conditional to reason about.
 *
 * <p>The securityMatcher is what keeps this chain narrow, and it must never widen — a chain at
 * {@code @Order(0)} matching more than these paths would take precedence over the real one for
 * every request it matched.
 */
@Configuration
@Profile("dev")
public class DevApiDocsSecurityConfig {

    @Bean
    @Order(0)
    SecurityFilterChain apiDocsFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(
                        "/v3/api-docs",
                        "/v3/api-docs.yaml",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
```

- [ ] **Step 5: Verify `SecurityConfig` is untouched**

```bash
cd /Users/divyam/Documents/easy-crm
git diff --stat main -- backend/src/main/java/com/easycrm/platform/security/SecurityConfig.java
```
Expected: **no output.** If this file appears in the diff, the task took the wrong approach — the whole point is that production authorization is unchanged.

- [ ] **Step 6: Run the full check**

Run: `cd /Users/divyam/Documents/easy-crm/backend && ./gradlew clean check`
Expected: PASS, **526 + 3 = 529** tests. `ApiDocsExposureTest` still passes, which now means the flags and the dev chain did not change the default posture.

- [ ] **Step 7: Verify the dev profile actually serves the UI**

The dev path is not covered by the suite (the suite never runs under the `dev` profile), so check it by hand once. Docker must be up and a local Postgres reachable, per the standing dev setup:

```bash
cd /Users/divyam/Documents/easy-crm/backend
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun
# in another shell:
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/v3/api-docs
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/swagger-ui/index.html
```
Expected: `200` for both. Stop the app afterwards.

If `bootRun` fails to start because the UI starter is on `developmentOnly`, apply Task 1 Step 8's fallback (move it to `implementation`) and note the reason — it weakens the structural guarantee and that should be on the record.

- [ ] **Step 8: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add backend/src/main/java/com/easycrm/platform/openapi/DevApiDocsSecurityConfig.java \
        backend/src/test/java/com/easycrm/platform/openapi/ApiDocsExposureTest.java \
        backend/src/main/resources/application.yml backend/src/main/resources/application-dev.yml
git commit -m "feat: expose api-docs and swagger-ui under the dev profile only

Two independent layers: springdoc's enabled flags are false by default so
the routes are never registered outside dev, and a dev-profile filter
chain at Order(0) permits them when they are. SecurityConfig is not
modified -- its terminal denyAll stays the production answer, and
deleting the new file restores today's behaviour exactly.

ApiDocsExposureTest asserts the non-dev posture, which is the one a
mistake here would turn into a permanently unauthenticated route."
```

---

### Task 6: oasdiff in CI

**Files:**
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: `docs/api/openapi.yaml` at exactly that path (Task 4).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Add `fetch-depth: 2` to the checkout**

`actions/checkout@v7` clones shallow at depth 1 by default, so `HEAD~1` does not exist in the CI working copy and reading the previous snapshot fails. This is the same category as the dormant `pull_request` trigger the handoff flags: configuration that reads as correct and does nothing.

In `.github/workflows/ci.yml`, replace `      - uses: actions/checkout@v7` with:

```yaml
      - uses: actions/checkout@v7
        with:
          # The oasdiff step below reads the previous commit's copy of the OpenAPI snapshot.
          # The default shallow clone (depth 1) has no HEAD~1, so that read would fail --
          # useful-looking config that silently does nothing.
          fetch-depth: 2
```

- [ ] **Step 2: Add the oasdiff step**

Append to the `check` job's `steps:`, after the existing "Upload reports" step. The job sets `working-directory: backend`, so this step overrides it to the repository root.

```yaml
      # The API changelog, reported and not enforced. CI here is a post-merge smoke alarm
      # (push: [main], and this repo has never opened a pull request), so a blocking gate would
      # fail after the breaking change had already landed -- a notification with extra steps.
      # There is also no consumer yet: members management, cursor pagination and the frontend's
      # own needs will all legitimately change endpoints, so a blocking gate would fire
      # regularly, at nobody, on correct work. That is how gates get ignored.
      #
      # Flip continue-on-error to false when the frontend exists and consumes this spec. At
      # that point there is a real party to break.
      - name: API changelog (oasdiff)
        if: github.event_name == 'push'
        continue-on-error: true
        working-directory: .
        run: |
          set -u
          SNAPSHOT=docs/api/openapi.yaml
          if ! git cat-file -e "HEAD~1:$SNAPSHOT" 2>/dev/null; then
            echo "No previous version of $SNAPSHOT - nothing to compare." >> "$GITHUB_STEP_SUMMARY"
            exit 0
          fi
          git show "HEAD~1:$SNAPSHOT" > /tmp/openapi-base.yaml
          echo "## API changelog" >> "$GITHUB_STEP_SUMMARY"
          docker run --rm \
            -v /tmp/openapi-base.yaml:/base.yaml:ro \
            -v "$PWD/$SNAPSHOT:/head.yaml:ro" \
            tufin/oasdiff:latest changelog /base.yaml /head.yaml \
            | tee -a "$GITHUB_STEP_SUMMARY"
          echo "" >> "$GITHUB_STEP_SUMMARY"
          echo "### Breaking changes" >> "$GITHUB_STEP_SUMMARY"
          docker run --rm \
            -v /tmp/openapi-base.yaml:/base.yaml:ro \
            -v "$PWD/$SNAPSHOT:/head.yaml:ro" \
            tufin/oasdiff:latest breaking /base.yaml /head.yaml \
            | tee -a "$GITHUB_STEP_SUMMARY"
```

`ubuntu-latest` ships Docker preinstalled — the same fact the existing Testcontainers comment in this workflow relies on — so no extra setup step is needed.

- [ ] **Step 3: Validate the workflow file parses**

```bash
cd /Users/divyam/Documents/easy-crm
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml')); print('ci.yml parses')"
```
Expected: `ci.yml parses`.

- [ ] **Step 4: Dry-run the comparison logic locally**

The step's shell logic can be exercised without GitHub. This proves both branches behave rather than discovering it on a real run:

```bash
cd /Users/divyam/Documents/easy-crm
# absent-base branch: a path with no previous version
git cat-file -e "HEAD~1:docs/api/does-not-exist.yaml" 2>/dev/null && echo "unexpected" || echo "absent-base branch taken: correct"
# compare branch, against a deliberately modified copy
cp docs/api/openapi.yaml /tmp/openapi-head.yaml
python3 -c "
p='/tmp/openapi-head.yaml'
s=open(p).read().replace('EasyCRM API','EasyCRM API (modified)',1)
open(p,'w').write(s)"
docker run --rm -v "$PWD/docs/api/openapi.yaml:/base.yaml:ro" -v /tmp/openapi-head.yaml:/head.yaml:ro \
  tufin/oasdiff:latest changelog /base.yaml /head.yaml
```
Expected: the absent-base branch reports correctly, and oasdiff produces non-empty output for the modified copy. Docker must be running.

- [ ] **Step 5: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add .github/workflows/ci.yml
git commit -m "ci: report the API changelog with oasdiff, without blocking

Compares the previous commit's OpenAPI snapshot against the new one on
every push to main and writes the changelog and breaking-change list into
the job summary.

Deliberately continue-on-error. CI here is post-merge and there is no
consumer yet, so a blocking gate would fail after the change had landed
and would fire on ordinary backend work -- which is how a red build gets
ignored. Flip it when the frontend consumes this spec.

checkout gains fetch-depth: 2: the default shallow clone has no HEAD~1,
so the comparison would have been config that reads as correct and does
nothing."
```

- [ ] **Step 6: Observe it on a real run, and report honestly**

Until this step has actually run, it is unproven config — the same untested-gate category as the dormant `pull_request` trigger.

```bash
cd /Users/divyam/Documents/easy-crm
git push -u origin openapi-contract
gh run list --branch openapi-contract --limit 3
```

Note the workflow triggers on `push: [main]` and on `pull_request`, so a push to a feature branch fires **nothing**. Two honest options: open a PR for this branch (which also finally exercises the dormant `pull_request` trigger — though the step's own `if: github.event_name == 'push'` guard means oasdiff itself will *not* run on a PR), or accept that the step is first exercised by the merge to `main`, where it will take the absent-base branch because the snapshot has no predecessor.

**Record which of these actually happened.** Do not claim the step is verified if it has not run.

---

### Task 7: Documentation wrap-up

**Files:**
- Modify: `docs/superpowers/engineering-challenges.md`
- Modify: `docs/superpowers/annotations-reference.md`
- Modify: `docs/superpowers/HANDOFF.md`
- Modify: `docs/superpowers/specs/2026-09-01-build-hygiene-design.md`

**Interfaces:** none.

- [ ] **Step 1: Log the engineering challenge**

Append to `docs/superpowers/engineering-challenges.md` using the template at the bottom of that file (Problem → why it's hard → Solution → Lesson). The strongest candidate is the one-generator problem:

> **Problem.** A committed API snapshot needs two things that pull against each other: something that writes it, and something that checks it.
>
> **Why it's hard.** The obvious shape — a Gradle plugin that boots the app and dumps the spec, plus a test that compares a file — has two independent code paths producing what is supposed to be the same document. When they disagree, the guard is asserting against something the generator never emits, and neither artefact means anything; worse, the disagreement is invisible until someone regenerates and the check *still* fails. Determinism compounds it: springdoc's internal maps have no guaranteed iteration order, so two runs over identical code can emit identical content in a different order and fail the guard for no real reason — an intermittent red build, which trains people to re-run rather than to look.
>
> **Solution.** One test in two modes. The same method generates the document and then either writes it (`updateOpenApiSnapshot`, a `Test` task that sets `-Dopenapi.write=true` and filters to that one class) or asserts against it. There is physically one generation path, so the guard cannot check something different from what the regeneration task emits. Ordering is pinned with `springdoc.writer-with-order-by-keys`, and that was *verified* rather than assumed — the property was confirmed present in the 3.1.0 jar before being depended on, and the output proven byte-stable across two runs.
>
> **Lesson.** When a guard and a generator produce the same artefact, make them literally the same code, not two implementations of one idea. And an ordering flag you have not verified is a scheduled intermittent failure.

Add a second entry only if Task 4 Step 6 forced the parsed-tree fallback, or if Task 2's key ordering actually moved.

- [ ] **Step 2: Update the annotations reference**

Add rows to `docs/superpowers/annotations-reference.md` matching the file's existing column layout (origin, purpose, meta-annotation composition), for any not already present:

- `@ParameterObject` — `org.springdoc.core.annotations`; expands a complex method parameter into its constituent query parameters in the generated document. Without it, `Pageable` renders as one nested object rather than `page`/`size`/`sort`.
- `@Hidden` — `io.swagger.v3.oas.annotations`; excludes a class or method from the generated document. Does **not** affect routing.
- `@ApiResponse`, `@Content`, `@Schema` — `io.swagger.v3.oas.annotations.responses` / `.media`; declare a response's status, description and schema on an `@ExceptionHandler`. One per distinct status code per advice; duplicates for one status are resolved arbitrarily.
- `@JsonInclude` — `com.fasterxml.jackson.annotation`; `NON_NULL` at type level omits null properties. Load-bearing here: without it the error envelope would emit `"fields":null` where it previously omitted the key entirely.
- `@Profile` — `org.springframework.context.annotation`; registers a bean only under the named profile.
- `@Order` on a `SecurityFilterChain` bean — `org.springframework.core.annotation`; decides which chain matches first.
- `@TestPropertySource` and `@AutoConfigureMockMvc(addFilters = false)` — add rows if absent; `addFilters = false` in particular deserves a note that it bypasses the security filter chain entirely, so a test using it asserts nothing about authorization.

- [ ] **Step 3: Update the handoff**

In `docs/superpowers/HANDOFF.md`:

- **§0** — replace the "Nothing is in flight" opening with this slice's status once merged: the merge commit, the verified-green test count, and the fact that `./gradlew clean check` now includes the OpenAPI drift guard. Move the build-hygiene paragraph down into history, matching how each previous slice was folded in.
- **§3** — add the inventory entry: springdoc 3.1.0, the snapshot at `docs/api/openapi.yaml`, the drift guard, the dev-only exposure, and the non-blocking oasdiff step with its flip trigger.
- **§8** — mark Wave 3 done. Re-rank what remains: Wave 1.5 (supply chain), the frontend, members management, Wave 2 (observability), and #4 cursor pagination. Note explicitly that the frontend now has a contract to build against and that wiring `/invite/{token}` is still the first task when it starts.
- Add the standing note that `docs/api/openapi.yaml` is **generated**: never hand-edited, and a merge conflict in it is always resolved by regenerating, never by hand-merging the YAML.

- [ ] **Step 4: Correct the controller count**

The figure "18 controllers" appears in both `docs/superpowers/HANDOFF.md` §8 and `docs/superpowers/specs/2026-09-01-build-hygiene-design.md` §3. The real count is **16**. Fix both.

```bash
cd /Users/divyam/Documents/easy-crm
grep -rn '18 controllers' docs/
find backend -name '*Controller.java' -not -path '*/build/*' | wc -l   # confirms 16
```

- [ ] **Step 5: Put the real test count in, not the predicted one**

```bash
cd /Users/divyam/Documents/easy-crm/backend && ./gradlew clean check
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'failures="[0-9]*"' {} + | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "failures:", s}'
```
Expected: **529** tests, 0 failures. If the number differs, reconcile it before writing it down — write the number the command prints, not the number this plan predicts.

- [ ] **Step 6: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add docs/superpowers/HANDOFF.md docs/superpowers/annotations-reference.md \
        docs/superpowers/engineering-challenges.md \
        docs/superpowers/specs/2026-09-01-build-hygiene-design.md
git commit -m "docs: record the OpenAPI contract slice

Handoff sections 0, 3 and 8; annotations reference rows for the springdoc
and Jackson annotations this slice introduced; and the challenge entry
for the one-generator problem -- a guard and a generator that produce the
same artefact have to be the same code, not two implementations of one
idea.

Corrects the controller count from 18 to 16 in the handoff and the
build-hygiene spec, and records that docs/api/openapi.yaml is generated
output: never hand-edited, and a conflict in it is resolved by
regenerating."
```

---

## Completion

Run the whole gate once more and read the output rather than assuming it:

```bash
cd /Users/divyam/Documents/easy-crm/backend && ./gradlew clean check
```

Then confirm each of these by observation, not by expectation:

- [ ] 529 tests, 0 failures, 0 errors, counted across **both** projects.
- [ ] `docs/api/openapi.yaml` exists, contains no `demo-records`, contains `ApiErrorResponse`, and renders `page`/`size`/`sort` as query parameters.
- [ ] The drift guard was seen to **fail** on a deliberate API change and pass after regeneration (Task 4 Step 7).
- [ ] The snapshot was seen to be byte-stable across two regenerations (Task 4 Step 6).
- [ ] `git diff main -- backend/src/main/java/com/easycrm/platform/security/SecurityConfig.java` is empty.
- [ ] No assertion outside `platform/error/` was edited in Task 2.
- [ ] `bootJar` contains the springdoc `-api` starter and no swagger-ui webjar.
- [ ] The oasdiff step's real-run status is recorded honestly — verified, or not yet exercised and why.

Then use `superpowers:finishing-a-development-branch` to merge.
