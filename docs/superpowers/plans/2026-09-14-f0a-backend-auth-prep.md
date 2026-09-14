# F0a — Backend Auth Prep Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the backend safe for a browser to hold a session and give the frontend a well-typed contract: refresh token in an httpOnly cookie, race-safe rotation with a 30 s lost-response grace window, identity on every session response, a CSRF header, field reason codes, `application/json` media types, a signup switch, and a looser rate limit for session resumption.

**Architecture:** All changes are in the existing Spring Boot monolith under `backend/`, mostly the `iam` package plus `platform.error`, `platform.security`, `platform-primitives` and `application.yml`. Services return an internal `IssuedSession(AuthResponse body, String refreshToken)`; only the two auth controllers turn the refresh token into a cookie. Rotation becomes conditional native UPDATEs, so a lost race is a 401 and a lost response is recoverable once. The OpenAPI snapshot is regenerated deliberately in each task that changes the contract.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring Security, Spring Data JPA / Hibernate, PostgreSQL 16 (Testcontainers), Flyway, springdoc 3.1.x, JUnit 5, MockMvc, ArchUnit, SnakeYAML (tests).

**Spec:** `docs/superpowers/specs/2026-09-14-f0-frontend-foundation-design.md` — this plan implements **Part 3 (F0a)** and the F0a items of Part 7. F0b (the frontend) gets its own plan after this merges, because its generated client is built from the contract this plan produces.

## Global Constraints

- Commit as the repo identity (`divyam <divyam.0444@gmail.com>`, plain `git commit`). **No `Co-Authored-By` trailer and no mention of Claude/AI in commit messages** (CLAUDE.md).
- Tenant isolation is structural: never hand-write `WHERE tenant_id = ?` on a tenant table. `refresh_token` is a GLOBAL, RLS-exempt table; queries on it are by `token_hash`/`id`, which is correct.
- `docs/api/openapi.yaml` is generated output — regenerate with `./gradlew updateOpenApiSnapshot`, never hand-edit.
- Every new gate or guard must be seen failing once (challenges #75–79). Where a step says "Expected: FAIL", confirm the failure reason matches before continuing.
- Refresh cookie: name `easycrm_rt`; attributes `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=2592000`.
- CSRF header: `X-EasyCRM-Client: web`, required on `POST /api/v1/auth/refresh` and `POST /api/v1/auth/logout`; missing → 403 with the standard envelope.
- Grace window: 30 seconds, single use, only while the successor token is still unused.
- Signup switch: `easycrm.signup.enabled`, from `SIGNUP_ENABLED`, default `true` in every profile; closed → 404 `"signup is closed"` checked before any slug lookup.
- Rate limits: new `session` policy (120/min per IP) for `/api/v1/auth/refresh`, `/api/v1/auth/logout`, `/api/v1/auth/me`, listed before `auth`; `auth` stays 30/min.
- Field reason codes are `SCREAMING_SNAKE`, stable once shipped, documented in `docs/api/error-codes.md`.
- Run all Gradle commands from `backend/`. Baseline before this plan: **626 tests (598 root + 28 `platform-primitives`), 0 failures**.
- Engineering challenges log: append using the template at `docs/superpowers/engineering-challenges.md` line ~2552; next number is **80**.

---

## File Structure

**Create**
- `backend/src/main/java/com/easycrm/iam/IssuedSession.java` — internal service result: response body + raw refresh token.
- `backend/src/main/java/com/easycrm/iam/SignupProperties.java` — `easycrm.signup.enabled`.
- `backend/src/main/java/com/easycrm/iam/web/RefreshCookie.java` — the only code that builds, reads or clears `easycrm_rt`.
- `backend/src/main/java/com/easycrm/iam/web/dto/SignupStatusResponse.java`
- `backend/src/main/resources/db/migration/V35__refresh_token_grace.sql`
- `backend/src/test/java/com/easycrm/platform/openapi/OpenApiMediaTypesTest.java`
- `backend/src/test/java/com/easycrm/iam/RefreshTokenRotationRaceTest.java`
- `backend/src/test/java/com/easycrm/iam/RefreshTokenGraceTest.java`
- `backend/src/test/java/com/easycrm/iam/web/AuthCookieTest.java`
- `backend/src/test/java/com/easycrm/iam/web/SignupClosedTest.java`
- `backend/src/test/java/com/easycrm/iam/SignupDefaultsTest.java`
- `backend/src/test/java/com/easycrm/arch/AuthSessionBoundaryArchTest.java`
- `backend/platform/platform-primitives/src/test/java/com/easycrm/platform/gst/StateCodeTest.java`
- `docs/api/error-codes.md`

**Modify**
- `backend/src/main/resources/application.yml` — springdoc media type, `session` policy, signup switch.
- `backend/src/main/java/com/easycrm/sales/web/QuotationController.java`, `PublicShareController.java` — `produces` on PDF mappings.
- `backend/platform/platform-primitives/src/main/java/com/easycrm/platform/error/ValidationException.java` — codes.
- `backend/platform/platform-primitives/src/main/java/com/easycrm/platform/gst/Gstin.java`, `StateCode.java` — codes.
- `backend/src/main/java/com/easycrm/platform/error/ApiError.java`, `ConflictException.java` (find with `find backend -name ConflictException.java -path '*src/main*'`), `ApiExceptionHandler.java` — `fieldCodes`.
- `backend/src/main/java/com/easycrm/iam/AuthService.java` — codes, case-insensitive login, `IssuedSession`, identity, signup switch.
- `backend/src/main/java/com/easycrm/iam/InvitationService.java` — `IssuedSession`.
- `backend/src/main/java/com/easycrm/iam/RefreshToken.java`, `RefreshTokenRepository.java`, `RefreshTokenService.java` — conditional rotation + grace.
- `backend/src/main/java/com/easycrm/iam/web/AuthController.java`, `PublicInvitationController.java` — cookie transport, header check, status route.
- `backend/src/main/java/com/easycrm/iam/web/dto/AuthResponse.java` — identity, no refresh token.
- `backend/src/main/java/com/easycrm/platform/security/SecurityConfig.java` — permit status route.
- `backend/src/main/java/com/easycrm/platform/openapi/OpenApiConfig.java` — description text.
- Tests: `AuthControllerTest`, `AuthServiceRefreshTest`, `AuthServiceSignupTest`, `AuthServiceLoginTest`, `InvitationAcceptTest`, `InvitationControllerTest`, `ApiErrorWireFormatTest`, `GstinTest`, `RateLimitDefaultsTest`.
- Docs: `docs/superpowers/engineering-challenges.md`, `docs/superpowers/annotations-reference.md`, `docs/superpowers/HANDOFF.md`, `docs/ROADMAP.md`, the spec (two wording corrections).

**Delete**
- `backend/src/main/java/com/easycrm/iam/web/dto/TokenResponse.java`
- `backend/src/main/java/com/easycrm/iam/web/dto/RefreshRequest.java`

---

### Task 1: Branch and clear the Dependabot backlog

**Files:** none authored; merges only.

**Interfaces:** Produces: branch `f0a-backend-auth` off an up-to-date `main` with green `clean check`.

- [ ] **Step 1: Confirm a clean start**

Run (from repo root):
```bash
git status --short
git fetch origin
git rev-parse --short main
git rev-parse --short origin/main
```
Expected: only `?? .tessl/` and `?? docs/architecture/pre-screening-answers.md` untracked (leave both alone). If `main` is behind `origin/main`, `git merge --ff-only origin/main`.

- [ ] **Step 2: List the Dependabot branches**

```bash
git branch -r | grep dependabot
```
Expected (as of 2026-09-14): `spotless-8.10.2`, `gradle-wrapper-9.7.1`, `jjwt-0.13.0`, `springdoc-3.1.1`, `test-tooling-0181f48dd2`.

- [ ] **Step 3: Merge each branch into `main`, one at a time, verifying after each**

For each branch `B` in the order `springdoc-3.1.1`, `jjwt-0.13.0`, `test-tooling-*`, `spotless-8.10.2`, `gradle-wrapper-9.7.1`:
```bash
git merge --no-edit origin/dependabot/gradle/backend/<B>
cd backend && ./gradlew clean check && cd ..
```
Expected: `BUILD SUCCESSFUL`.
- If `OpenApiSnapshotTest` fails after springdoc: run `./gradlew updateOpenApiSnapshot`, inspect `git diff docs/api/openapi.yaml` (it must be formatting/ordering only, no endpoint changes), then `git commit -am "build: regenerate OpenAPI snapshot for springdoc 3.1.1"`.
- If spotless fails after the spotless bump: `./gradlew spotlessApply`, confirm the diff is formatting only, commit `style: apply spotless 8.10.2 formatting`.
- If jjwt fails to compile or `JwtServiceTest` fails: **stop**, `git merge --abort` or `git reset --hard HEAD~1` for that merge only, and report the failure — do not rewrite JWT code inside this plan.
- If any other branch fails `check`, skip it (reset that merge) and record which in the Task 13 handoff note.

- [ ] **Step 4: Record the new baseline and cut the branch**

```bash
cd backend && ./gradlew clean check 2>&1 | tail -5 && cd ..
find backend -path '*build/test-results/test/*.xml' -print0 | xargs -0 grep -ho 'tests="[0-9]*"' | awk -F'"' '{s+=$2} END {print s}'
git switch -c f0a-backend-auth
```
Expected: the count is **626** (tests do not change with dependency bumps). If it differs, stop and reconcile before continuing. Write the number down; Task 13 restates the final count against it.

---

### Task 2: Every JSON response is keyed `application/json`

**Files:**
- Create: `backend/src/test/java/com/easycrm/platform/openapi/OpenApiMediaTypesTest.java`
- Modify: `backend/src/main/resources/application.yml` (the `springdoc:` block)
- Modify: `backend/src/main/java/com/easycrm/sales/web/QuotationController.java:77`
- Modify: `backend/src/main/java/com/easycrm/sales/web/PublicShareController.java:40`
- Regenerate: `docs/api/openapi.yaml`

**Interfaces:**
- Consumes: the `openapi.snapshot` system property set for every `Test` task in `backend/build.gradle.kts:108`.
- Produces: a contract with zero `*/*` response keys, which F0b's `openapi-typescript` depends on.

- [ ] **Step 1: Write the failing guard test**

```java
package com.easycrm.platform.openapi;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * openapi-typescript keys response types by media type, so a response keyed '*' + '/' + '*'
 * gives the generated frontend client an untyped body. This reads the COMMITTED snapshot, which
 * OpenApiSnapshotTest already holds equal to what springdoc generates.
 *
 * <p>Non-vacuity: the guard also asserts JSON responses exist in quantity and that both PDF routes
 * are keyed application/pdf — a document that parsed to nothing, or a walker that visited nothing,
 * cannot pass.
 */
class OpenApiMediaTypesTest {

    private static final String ANY = "*/*";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> paths() throws Exception {
        String yaml = Files.readString(Path.of(System.getProperty("openapi.snapshot")), StandardCharsets.UTF_8);
        Map<String, Object> doc = new Yaml().load(yaml);
        return (Map<String, Object>) doc.get("paths");
    }

    /** Every (path, method, status, mediaType) the document declares. */
    @SuppressWarnings("unchecked")
    private static List<String[]> responseMediaTypes() throws Exception {
        List<String[]> out = new ArrayList<>();
        for (var path : paths().entrySet()) {
            for (var op : ((Map<String, Object>) path.getValue()).entrySet()) {
                if (!(op.getValue() instanceof Map<?, ?> opMap)) continue;
                Object responses = opMap.get("responses");
                if (!(responses instanceof Map<?, ?> resMap)) continue;
                for (var res : resMap.entrySet()) {
                    Object content = ((Map<String, Object>) res.getValue()).get("content");
                    if (!(content instanceof Map<?, ?> contentMap)) continue;
                    for (Object mediaType : contentMap.keySet()) {
                        out.add(new String[] {path.getKey(), op.getKey(), String.valueOf(res.getKey()), (String) mediaType});
                    }
                }
            }
        }
        return out;
    }

    @Test
    void noResponseIsKeyedAnyMediaType() throws Exception {
        List<String> offenders = responseMediaTypes().stream()
                .filter(r -> ANY.equals(r[3]))
                .map(r -> r[1].toUpperCase() + " " + r[0] + " " + r[2])
                .toList();
        assertTrue(offenders.isEmpty(), offenders.size() + " responses keyed */*, e.g. "
                + offenders.stream().limit(5).toList());
    }

    @Test
    void jsonResponsesExistInQuantity() throws Exception {
        long json = responseMediaTypes().stream().filter(r -> "application/json".equals(r[3])).count();
        assertTrue(json >= 400, "only " + json + " application/json responses; the walker or document is broken");
    }

    @Test
    void bothPdfRoutesDeclareApplicationPdfOnSuccess() throws Exception {
        for (String route : List.of("/api/v1/quotations/{id}/pdf", "/public/q/{token}")) {
            List<String> ok = responseMediaTypes().stream()
                    .filter(r -> r[0].equals(route) && r[1].equals("get") && r[2].equals("200"))
                    .map(r -> r[3])
                    .toList();
            assertEquals(List.of("application/pdf"), ok, route + " 200 response media types");
        }
    }
}
```

- [ ] **Step 2: Run it and confirm the failure reasons**

Run: `./gradlew test --tests 'com.easycrm.platform.openapi.OpenApiMediaTypesTest'`
Expected: FAIL. `noResponseIsKeyedAnyMediaType` reports ~458 offenders; `jsonResponsesExistInQuantity` fails (32 < 400); `bothPdfRoutesDeclareApplicationPdfOnSuccess` fails (`*/*`). All three failing proves each assertion can fail.

- [ ] **Step 3: Set the default media type**

In `application.yml`, inside the existing top-level `springdoc:` block, directly under `writer-with-order-by-keys: true`, add:
```yaml
  # Without this springdoc keys every response '*/*', and openapi-typescript (the frontend's
  # generated client) types a body by its media type -- so the client would be untyped.
  # OpenApiMediaTypesTest guards it. PDF routes declare produces explicitly instead.
  default-produces-media-type: application/json
```

- [ ] **Step 4: Declare `produces` on the two PDF mappings**

`QuotationController.java` — change the annotation on `pdf(...)`:
```java
    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
```
`PublicShareController.java` — change the annotation on `quotation(...)`:
```java
    @GetMapping(value = "/{token}", produces = MediaType.APPLICATION_PDF_VALUE)
```
(`MediaType` is already imported in both files.)

- [ ] **Step 5: Regenerate the snapshot and inspect it**

```bash
./gradlew updateOpenApiSnapshot
git diff --stat ../docs/api/openapi.yaml
grep -c "'\*/\*'" ../docs/api/openapi.yaml
grep -n -A12 "/api/v1/quotations/{id}/pdf:" ../docs/api/openapi.yaml | head -30
```
Expected: `grep -c` prints `0`. Note in the commit body what the PDF routes' **error** responses (404 etc.) are keyed as — springdoc applies the operation's `produces` to advice responses too, so they may show `application/pdf`. That is acceptable for F0 (the frontend downloads PDFs as blobs, not through typed errors); record it, do not work around it.

- [ ] **Step 6: Run the contract tests**

Run: `./gradlew test --tests 'com.easycrm.platform.openapi.*'`
Expected: PASS (including `OpenApiSnapshotTest`, `OasdiffWorkflowTest`).

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/application.yml src/main/java/com/easycrm/sales/web/QuotationController.java \
  src/main/java/com/easycrm/sales/web/PublicShareController.java \
  src/test/java/com/easycrm/platform/openapi/OpenApiMediaTypesTest.java ../docs/api/openapi.yaml
git commit -m "feat(api): key every JSON response application/json

springdoc defaulted all 458 responses to */*, which leaves a generated
TypeScript client untyped. PDF routes now declare produces explicitly.
OpenApiMediaTypesTest guards both, with non-vacuity assertions."
```

---

### Task 3: Field reason codes in the error envelope

**Files:**
- Modify: `backend/platform/platform-primitives/src/main/java/com/easycrm/platform/error/ValidationException.java`
- Modify: `backend/platform/platform-primitives/src/main/java/com/easycrm/platform/gst/Gstin.java:20-36`
- Modify: `backend/platform/platform-primitives/src/main/java/com/easycrm/platform/gst/StateCode.java:26-28`
- Modify: `backend/src/main/java/com/easycrm/platform/error/ApiError.java`
- Modify: `backend/src/main/java/com/easycrm/platform/error/ConflictException.java`
- Modify: `backend/src/main/java/com/easycrm/platform/error/ApiExceptionHandler.java`
- Modify: `backend/src/main/java/com/easycrm/iam/AuthService.java` (slug conflict, state mismatch)
- Test: `backend/platform/platform-primitives/src/test/java/com/easycrm/platform/gst/GstinTest.java`, create `StateCodeTest.java`
- Test: `backend/src/test/java/com/easycrm/platform/error/ApiErrorWireFormatTest.java`
- Test: `backend/src/test/java/com/easycrm/iam/web/AuthControllerTest.java`
- Create: `docs/api/error-codes.md`
- Regenerate: `docs/api/openapi.yaml`

**Interfaces:**
- Produces:
  - `ValidationException(String field, String message, String code)`; `ValidationException(Map<String,String> fields, Map<String,String> codes)`; `Map<String,String> getCodes()` (never null, may be empty).
  - `ConflictException(String message, Map<String,Object> fields, Map<String,String> fieldCodes)`; `Map<String,String> getFieldCodes()` (null when none).
  - `ApiError(String code, String message, Map<String,Object> fields, Map<String,String> fieldCodes)` plus the existing 3-arg form.
  - Wire: `{"error":{"code","message","fields"?,"fieldCodes"?}}`.
  - Codes: `GSTIN_REQUIRED`, `GSTIN_LENGTH`, `GSTIN_CHARSET`, `GSTIN_CHECKSUM`, `STATE_CODE_INVALID`, `STATE_CODE_GSTIN_MISMATCH`, `SLUG_TAKEN`; bean validation → constraint name in SCREAMING_SNAKE (`NOT_BLANK`, `SIZE`, `PATTERN`, `EMAIL`).

- [ ] **Step 1: Write failing primitive tests**

Append to `GstinTest.java`:
```java
    @Test
    void everyRejectionCarriesAStableReasonCode() {
        assertEquals("GSTIN_REQUIRED", assertThrows(ValidationException.class, () -> Gstin.parse(null)).getCodes().get("gstin"));
        assertEquals("GSTIN_LENGTH", assertThrows(ValidationException.class, () -> Gstin.parse("27AAPFU0939F1Z")).getCodes().get("gstin"));
        assertEquals("GSTIN_CHARSET", assertThrows(ValidationException.class, () -> Gstin.parse("27AAPFU0939F1Z!")).getCodes().get("gstin"));
        assertEquals("GSTIN_CHECKSUM", assertThrows(ValidationException.class, () -> Gstin.parse("27AAPFU0939F1ZZ")).getCodes().get("gstin"));
    }
```
Create `StateCodeTest.java`:
```java
package com.easycrm.platform.gst;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.platform.error.ValidationException;
import org.junit.jupiter.api.Test;

class StateCodeTest {

    @Test
    void anInvalidCodeCarriesAStableReasonCode() {
        ValidationException ex = assertThrows(ValidationException.class, () -> StateCode.requireValid("99"));
        assertEquals("invalid GST state code", ex.getFields().get("stateCode"));
        assertEquals("STATE_CODE_INVALID", ex.getCodes().get("stateCode"));
    }

    @Test
    void anUncodedExceptionHasAnEmptyCodeMap() {
        assertTrue(new ValidationException("x", "y").getCodes().isEmpty());
    }
}
```

- [ ] **Step 2: Run and confirm compile failure**

Run: `./gradlew :platform:platform-primitives:test --tests 'com.easycrm.platform.gst.*'`
Expected: FAIL — `cannot find symbol: method getCodes()`.

- [ ] **Step 3: Implement codes in the primitives**

Replace `ValidationException.java` with:
```java
package com.easycrm.platform.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Field-level domain validation failure. Mapped to HTTP 422 by ApiExceptionHandler.
 *
 * <p>{@code codes} maps a field to a stable, machine-readable reason (SCREAMING_SNAKE, see
 * docs/api/error-codes.md) so a client can translate the error instead of displaying English
 * prose. Optional: an exception built without codes has an empty map, and the client falls back to
 * the message. LinkedHashMap for a deterministic serialized key order (see ApiError).
 */
public class ValidationException extends RuntimeException {

    private final Map<String, String> fields;
    private final Map<String, String> codes;

    public ValidationException(Map<String, String> fields) {
        this(fields, Map.of());
    }

    public ValidationException(Map<String, String> fields, Map<String, String> codes) {
        super("validation failed");
        this.fields = fields;
        this.codes = Collections.unmodifiableMap(new LinkedHashMap<>(codes));
    }

    public ValidationException(String field, String message) {
        this(Map.of(field, message));
    }

    public ValidationException(String field, String message, String code) {
        this(Map.of(field, message), Map.of(field, code));
    }

    public Map<String, String> getFields() {
        return fields;
    }

    /** Never null; empty when the thrower supplied no codes. */
    public Map<String, String> getCodes() {
        return codes;
    }
}
```
In `Gstin.parse`, change the four throws to pass codes:
```java
        if (raw == null) throw new ValidationException("gstin", "GSTIN is required", "GSTIN_REQUIRED");
        String g = raw.trim().toUpperCase();
        if (g.length() != 15) throw new ValidationException("gstin", "GSTIN must be 15 characters", "GSTIN_LENGTH");
        for (int i = 0; i < 15; i++) {
            if (CHARSET.indexOf(g.charAt(i)) < 0)
                throw new ValidationException("gstin", "GSTIN has invalid characters", "GSTIN_CHARSET");
        }
        if (checkChar(g.substring(0, 14)) != g.charAt(14))
            throw new ValidationException("gstin", "GSTIN checksum is invalid", "GSTIN_CHECKSUM");
```
In `StateCode.requireValid`:
```java
        if (!isValid(code)) throw new ValidationException("stateCode", "invalid GST state code", "STATE_CODE_INVALID");
```

- [ ] **Step 4: Run primitive tests**

Run: `./gradlew :platform:platform-primitives:test`
Expected: PASS (all 28 + 3 new).

- [ ] **Step 5: Write failing envelope and endpoint tests**

Append to `ApiErrorWireFormatTest.java` (it already has `handler`, `mapper`, `json(...)`):
```java
    @Test
    void codedValidationCarriesFieldCodesBesideFields() throws Exception {
        var tree = mapper.readTree(json(handler.validation(
                new ValidationException("gstin", "GSTIN checksum is invalid", "GSTIN_CHECKSUM"))));

        assertEquals("GSTIN checksum is invalid", tree.at("/error/fields/gstin").asString());
        assertEquals("GSTIN_CHECKSUM", tree.at("/error/fieldCodes/gstin").asString());
    }

    @Test
    void anUncodedErrorOmitsTheFieldCodesKeyEntirely() throws Exception {
        String body = json(handler.validation(new ValidationException("gstin", "GSTIN checksum is invalid")));
        assertFalse(body.contains("fieldCodes"), "absent codes must not serialize, keeping existing bytes identical");
    }

    @Test
    void aConflictCanCarryFieldsAndCodes() throws Exception {
        var tree = mapper.readTree(json(handler.conflict(new ConflictException(
                "slug already taken",
                java.util.Map.of("slug", "slug already taken"),
                java.util.Map.of("slug", "SLUG_TAKEN")))));

        assertEquals("SLUG_TAKEN", tree.at("/error/fieldCodes/slug").asString());
    }
```
(If `asString()` does not exist on this Jackson 3 `JsonNode`, use `asText()` — check with `grep -rn "asText\|asString" src/test | head -3` and follow the existing usage.)

Append to `AuthControllerTest.java` (add imports `static org.hamcrest.Matchers.*` is not needed; use `jsonPath(...).value(...)`):
```java
    @Test
    void beanValidationFailuresCarryConstraintCodes() throws Exception {
        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"slug":"codes-a","businessName":"Codes","stateCode":"27","email":"o@codes-a.test"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldCodes.password").value("NOT_BLANK"));
    }

    @Test
    void aTakenSlugLandsOnTheSlugFieldWithACode() throws Exception {
        String body = """
            {"slug":"codes-dupe","businessName":"Codes","stateCode":"27",
             "email":"%s","password":"correct-horse"}""";
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body.formatted("a@codes.test")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body.formatted("b@codes.test")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.fields.slug").exists())
                .andExpect(jsonPath("$.error.fieldCodes.slug").value("SLUG_TAKEN"));
    }

    @Test
    void aSellerGstinDisagreeingWithTheStateCarriesAMismatchCode() throws Exception {
        // 27AAPFU0939F1ZV is a valid Maharashtra (27) GSTIN; stateCode 29 is Karnataka.
        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"slug":"codes-mismatch","businessName":"Codes","stateCode":"29","gstin":"27AAPFU0939F1ZV",
                             "email":"o@codes-m.test","password":"correct-horse"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.fieldCodes.stateCode").value("STATE_CODE_GSTIN_MISMATCH"));
    }
```

- [ ] **Step 6: Run and confirm the failures**

Run: `./gradlew test --tests 'com.easycrm.platform.error.ApiErrorWireFormatTest' --tests 'com.easycrm.iam.web.AuthControllerTest'`
Expected: compile FAIL on the 3-arg `ConflictException`; after Step 7's `ConflictException` change alone, the `fieldCodes` assertions would still fail — that is the failure being fixed.

- [ ] **Step 7: Implement `fieldCodes` in the envelope**

`ApiError.java` — replace the record header and compact constructor (keep the existing Javadoc and comments above the constructor), and add the 3-arg constructor:
```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, Map<String, Object> fields, Map<String, String> fieldCodes) {

    // (existing defensive-copy comment block stays here)
    public ApiError {
        fields = fields == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        // Same copy discipline as fields. Null (not empty) when absent, so NON_NULL omits the key
        // and every existing error keeps its exact bytes.
        fieldCodes = fieldCodes == null || fieldCodes.isEmpty()
                ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(fieldCodes));
    }

    public ApiError(String code, String message, Map<String, Object> fields) {
        this(code, message, fields, null);
    }
}
```
Also add to the class Javadoc: `<p>{@code fieldCodes} maps a field to a stable reason code (docs/api/error-codes.md); omitted when there are none.`

`ConflictException.java` — add a field, a constructor and a getter; make the 2-arg constructor delegate:
```java
    private final Map<String, Object> fields;
    private final Map<String, String> fieldCodes;

    public ConflictException(String message) {
        this(message, null, null);
    }

    public ConflictException(String message, Map<String, Object> fields) {
        this(message, fields, null);
    }

    public ConflictException(String message, Map<String, Object> fields, Map<String, String> fieldCodes) {
        super(message);
        this.fields = fields == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        this.fieldCodes = fieldCodes == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(fieldCodes));
    }

    /** Null when this conflict carries no reason codes — the common case. */
    public Map<String, String> getFieldCodes() {
        return fieldCodes;
    }
```

`ApiExceptionHandler.java`:
- `conflict(...)`: `return body(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage(), ex.getFields(), ex.getFieldCodes());`
- `validation(...)`: `return body(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "request is invalid", fields, ex.getCodes());`
- `invalid(...)`:
```java
    public ResponseEntity<ApiErrorResponse> invalid(MethodArgumentNotValidException ex) {
        Map<String, Object> fields = new HashMap<>();
        Map<String, String> codes = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> {
            fields.put(fe.getField(), fe.getDefaultMessage());
            codes.put(fe.getField(), constraintCode(fe.getCode()));
        });
        return body(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "request is invalid", fields, codes);
    }

    /** "NotBlank" -> "NOT_BLANK". Bean Validation's constraint name is already stable per annotation. */
    static String constraintCode(String constraint) {
        if (constraint == null) return "INVALID";
        return constraint.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT);
    }
```
- Replace the private `body(...)` with two overloads:
```java
    private ResponseEntity<ApiErrorResponse> body(
            HttpStatus status, String code, String message, Map<String, Object> fields) {
        return body(status, code, message, fields, null);
    }

    private ResponseEntity<ApiErrorResponse> body(
            HttpStatus status, String code, String message, Map<String, Object> fields, Map<String, String> fieldCodes) {
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(new ApiError(code, message, fields, fieldCodes)));
    }
```

`AuthService.signup`:
```java
        if (tenants.findBySlug(req.slug()).isPresent()) {
            throw new ConflictException(
                    "slug already taken", Map.of("slug", "slug already taken"), Map.of("slug", "SLUG_TAKEN"));
        }
```
and
```java
                throw new ValidationException("stateCode", "must match the GSTIN state code", "STATE_CODE_GSTIN_MISMATCH");
```

- [ ] **Step 8: Run the tests**

Run: `./gradlew test --tests 'com.easycrm.platform.error.*' --tests 'com.easycrm.iam.*' --tests 'com.easycrm.crm.web.CustomerControllerTest'`
Expected: PASS. (`ApiErrorWireFormatTest.errorWithFieldsCarriesThemNested` must still pass unchanged — it proves uncoded bytes did not move.)

- [ ] **Step 9: Write `docs/api/error-codes.md`**

```markdown
# API error reason codes

Every error uses one envelope: `{"error":{"code","message","fields"?,"fieldCodes"?}}`.

- `code` — the error class: `VALIDATION_FAILED` (400 bean validation, 422 domain validation),
  `CONFLICT` (409), `NOT_FOUND`, `UNAUTHORIZED`, `FORBIDDEN`, `RATE_LIMITED` (429).
- `fields` — field → English message, for display fallback only.
- `fieldCodes` — field → stable reason code. **Clients translate these; they never parse `message`
  or `fields` text.** Omitted when the thrower supplied none.

## Rules

1. Codes are `SCREAMING_SNAKE` and **never renamed or reused** once shipped. Retire by ceasing to emit.
2. Bean-validation failures get codes automatically: the constraint annotation name in
   SCREAMING_SNAKE (`@NotBlank` → `NOT_BLANK`, `@Size` → `SIZE`, `@Pattern` → `PATTERN`, `@Email` → `EMAIL`).
3. A hand-thrown `ValidationException` or `ConflictException` a frontend form will display **must**
   pass a code (`new ValidationException(field, message, CODE)`). Add the code to the table below in
   the same change.

## Domain codes

| Code | Field | Thrown by | Meaning |
|---|---|---|---|
| `GSTIN_REQUIRED` | `gstin` | `Gstin.parse` | null GSTIN where one is required |
| `GSTIN_LENGTH` | `gstin` | `Gstin.parse` | not 15 characters |
| `GSTIN_CHARSET` | `gstin` | `Gstin.parse` | a character outside 0-9 A-Z |
| `GSTIN_CHECKSUM` | `gstin` | `Gstin.parse` | check digit does not match |
| `STATE_CODE_INVALID` | `stateCode` | `StateCode.requireValid` | not a GST state code (also raised for a GSTIN whose first two characters are not one) |
| `STATE_CODE_GSTIN_MISMATCH` | `stateCode` | `AuthService.signup` | seller state code differs from the GSTIN's prefix |
| `SLUG_TAKEN` | `slug` | `AuthService.signup` | workspace slug already exists |
```

- [ ] **Step 10: Regenerate the contract, run the full suite, commit**

```bash
./gradlew updateOpenApiSnapshot
git diff ../docs/api/openapi.yaml | grep "^[+-]" | head -20   # expect fieldCodes added to ApiError
./gradlew check
git add -A src platform ../docs/api/openapi.yaml ../docs/api/error-codes.md
git commit -m "feat(api): add machine-readable fieldCodes to the error envelope

Bean-validation failures get the constraint name as a code; GSTIN, state
code and slug-taken errors get domain codes. Additive and omitted when
absent, so existing error bytes are unchanged. Documented in
docs/api/error-codes.md."
```
Expected: `BUILD SUCCESSFUL`.

---

### Task 4: Login matches email case-insensitively

**Files:**
- Modify: `backend/src/main/java/com/easycrm/iam/AuthService.java:133`
- Test: `backend/src/test/java/com/easycrm/iam/AuthServiceLoginTest.java`

**Interfaces:** Consumes `UserRepository.findByEmailIgnoreCase(String)` (exists, line 24).

- [ ] **Step 1: Write the failing test**

Append to `AuthServiceLoginTest.java` (it has a `signup(slug, email, pass)` helper):
```java
    @Test
    void loginIgnoresEmailCase() {
        // V32 made membership uniqueness case-insensitive; an Android keyboard capitalises the first
        // letter. Login must agree with the uniqueness rule, or the user gets the generic 401.
        signup("login-case", "ravi@login-case.test", "correct-horse");
        TenantContext.clear();

        var res = auth.login(new LoginRequest("login-case", "Ravi@Login-Case.test", "correct-horse"));

        assertNotNull(res.accessToken());
    }
```

- [ ] **Step 2: Run it**

Run: `./gradlew test --tests 'com.easycrm.iam.AuthServiceLoginTest.loginIgnoresEmailCase'`
Expected: FAIL with `UnauthorizedException: invalid credentials`.

- [ ] **Step 3: Implement**

In `AuthService.login`, change `users.findByEmail(req.email())` to `users.findByEmailIgnoreCase(req.email())`.

- [ ] **Step 4: Run the login tests**

Run: `./gradlew test --tests 'com.easycrm.iam.AuthServiceLoginTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/easycrm/iam/AuthService.java src/test/java/com/easycrm/iam/AuthServiceLoginTest.java
git commit -m "fix(auth): match login email case-insensitively

V32 made membership uniqueness case-insensitive but login still used an
exact match, so an auto-capitalised email failed with the generic 401."
```

---

### Task 5: Losing a rotation race is a 401, not a 409

**Files:**
- Create: `backend/src/test/java/com/easycrm/iam/RefreshTokenRotationRaceTest.java`
- Modify: `backend/src/main/java/com/easycrm/iam/RefreshTokenRepository.java`
- Modify: `backend/src/main/java/com/easycrm/iam/RefreshTokenService.java`

**Interfaces:**
- Produces:
  - `RefreshTokenService.rotate(String rawToken)` and `rotate(String rawToken, Instant now)`, both `@Transactional`, returning `RotationResult(String newRawToken, UUID userId, UUID tenantId)`; throw `UnauthorizedException("invalid refresh token")`.
  - `public static final long TTL_DAYS = 30` (was private).
  - `RefreshTokenRepository.revokeIfLive(String hash, UUID successorId, Instant now) : int`.
- Consumes: `IntegrationTest.ownerConnection()`, `TokenHasher.sha256Hex`, the `Clock` bean.

- [ ] **Step 1: Write the deterministic race test**

```java
package com.easycrm.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.platform.error.UnauthorizedException;
import com.easycrm.support.IntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Two rotations of one refresh token, forced to overlap deterministically: a second connection
 * performs the "winning" rotation inside an open transaction, holding the row lock, while the
 * service's rotate() runs and blocks on it. Then the winner commits.
 *
 * <p>Before the fix, rotate() read the row unlocked, saw it live, and lost at its @Version check
 * on commit, surfacing ObjectOptimisticLockingFailureException — which ApiExceptionHandler maps to
 * 409. A refresh failure must be a 401. See spec 2026-09-14-f0 Part 2 #4 and §3.3.
 */
class RefreshTokenRotationRaceTest extends IntegrationTest {

    @Autowired
    RefreshTokenService refreshTokens;

    @Autowired
    TokenHasher hasher;

    @Test
    void theLoserOfAConcurrentRotationGets401AndLeavesNoToken() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String raw = refreshTokens.issue(userId, tenantId);
        ExecutorService pool = Executors.newSingleThreadExecutor();

        try (Connection winner = ownerConnection()) {
            winner.setAutoCommit(false);
            try (PreparedStatement ps = winner.prepareStatement(
                    "UPDATE refresh_token SET revoked_at = now(), replaced_by_id = ?, version = version + 1 "
                            + "WHERE token_hash = ?")) {
                // A successor id that does not exist: this test is about the race, not about grace
                // (RefreshTokenGraceTest owns that), so the loser must find nothing to recover.
                ps.setObject(1, UUID.randomUUID());
                ps.setString(2, hasher.sha256Hex(raw));
                assertEquals(1, ps.executeUpdate());
            }

            Future<Throwable> loser = pool.submit(() -> {
                try {
                    refreshTokens.rotate(raw);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });

            awaitABlockedStatementOn("refresh_token", Duration.ofSeconds(10));
            winner.commit();

            Throwable outcome = loser.get(10, TimeUnit.SECONDS);
            assertInstanceOf(UnauthorizedException.class, outcome, "loser outcome was " + outcome);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, liveTokensFor(userId), "the loser's successor insert must have rolled back");
    }

    private static void awaitABlockedStatementOn(String table, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        try (Connection c = ownerConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query ILIKE ?")) {
            ps.setString(1, "%" + table + "%");
            while (Instant.now().isBefore(deadline)) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getLong(1) > 0) return;
                }
                Thread.sleep(25);
            }
        }
        fail("rotate() never blocked on the held row lock; the race was not forced");
    }

    private static long liveTokensFor(UUID userId) throws Exception {
        try (Connection c = ownerConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
```

- [ ] **Step 2: Run it on today's code and confirm the RIGHT failure**

Run: `./gradlew test --tests 'com.easycrm.iam.RefreshTokenRotationRaceTest'`
Expected: FAIL at `assertInstanceOf` with `loser outcome was org.springframework.orm.ObjectOptimisticLockingFailureException`. If instead it fails with "rotate() never blocked", the race was not forced — do not proceed; investigate the wait query. If it passes, stop: the premise is wrong and the spec must be revisited.

- [ ] **Step 3: Add the conditional UPDATE to the repository**

Add to `RefreshTokenRepository.java` (imports: `org.springframework.data.jpa.repository.Modifying`, `org.springframework.data.jpa.repository.Query`, `org.springframework.data.repository.query.Param`, `java.time.Instant`):
```java
    /**
     * The race-safe half of rotation: revokes the presented token ONLY if it is still live, in one
     * statement. Postgres row-locks the target, so of two concurrent rotations the second blocks,
     * re-evaluates {@code revoked_at IS NULL} against the committed row, and matches zero rows.
     *
     * <p>Native, so it bypasses @Version and auditing: both are maintained by hand here.
     * {@code flushAutomatically} writes the pending successor INSERT first, so the id this row points
     * at exists in the same transaction.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE refresh_token
               SET revoked_at = :now, replaced_by_id = :successorId, version = version + 1, updated_at = :now
             WHERE token_hash = :hash AND revoked_at IS NULL AND expires_at > :now
            """, nativeQuery = true)
    int revokeIfLive(@Param("hash") String hash, @Param("successorId") UUID successorId, @Param("now") Instant now);
```

- [ ] **Step 4: Rewrite `rotate` in `RefreshTokenService`**

Change the class to take a `Clock` (imports `java.time.Clock`) and replace `TTL_DAYS` and `rotate`:
```java
    public static final long TTL_DAYS = 30;
    ...
    private final RefreshTokenRepository tokens;
    private final TokenHasher hasher;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenRepository tokens, TokenHasher hasher, Clock clock) {
        this.tokens = tokens;
        this.hasher = hasher;
        this.clock = clock;
    }

    @Transactional
    public RotationResult rotate(String rawToken) {
        return rotate(rawToken, clock.instant());
    }

    /**
     * {@code now} is explicit so tests can move time (ClockConfig: no test overrides the Clock bean).
     *
     * <p>Order matters: the successor is inserted first so the conditional UPDATE can point at it,
     * and if the UPDATE matches nothing the throw rolls the insert back with it. The presented row is
     * read only for its owner; it is never modified through the entity, so no @Version write can lose
     * a race here and surface as a 409.
     */
    @Transactional
    public RotationResult rotate(String rawToken, Instant now) {
        String hash = hasher.sha256Hex(rawToken);
        RefreshToken presented = tokens.findByTokenHash(hash).orElseThrow(RefreshTokenService::invalid);
        UUID userId = presented.getUserId();
        UUID tenantId = presented.getTenantId();

        String newRaw = randomToken();
        RefreshToken successor = tokens.save(
                new RefreshToken(hasher.sha256Hex(newRaw), userId, tenantId, now.plus(TTL_DAYS, ChronoUnit.DAYS)));

        if (tokens.revokeIfLive(hash, successor.getId(), now) == 1) {
            return new RotationResult(newRaw, userId, tenantId);
        }
        throw invalid();
    }

    private static UnauthorizedException invalid() {
        return new UnauthorizedException("invalid refresh token");
    }
```
Keep `issue`, `revoke`, `revokeAllForUser`, `randomToken` as they are, except `issue` uses `clock.instant()` instead of `Instant.now()`.

- [ ] **Step 5: Run the race test and the existing refresh tests**

Run: `./gradlew test --tests 'com.easycrm.iam.RefreshTokenRotationRaceTest' --tests 'com.easycrm.iam.RefreshTokenServiceTest' --tests 'com.easycrm.iam.AuthServiceRefreshTest' --tests 'com.easycrm.iam.MemberDisableTest'`
Expected: PASS. If `RefreshTokenServiceTest` constructs the service by hand, add `Clock.systemUTC()` as the third argument there.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/easycrm/iam/RefreshTokenRepository.java src/main/java/com/easycrm/iam/RefreshTokenService.java \
  src/test/java/com/easycrm/iam/
git commit -m "fix(auth): make refresh rotation a conditional update; a lost race is 401

@Version already stopped two rotations forking the session, but the loser
surfaced ObjectOptimisticLockingFailureException, which maps to 409. The
revoke is now one conditional UPDATE; a zero-row match is a 401 and rolls
back the loser's successor. The race test holds the row lock from a second
connection so the overlap is forced, and was seen failing with the 409 first."
```

---

### Task 6: A lost refresh response is recoverable once, within 30 seconds

**Files:**
- Create: `backend/src/main/resources/db/migration/V35__refresh_token_grace.sql`
- Create: `backend/src/test/java/com/easycrm/iam/RefreshTokenGraceTest.java`
- Modify: `backend/src/main/java/com/easycrm/iam/RefreshToken.java`
- Modify: `backend/src/main/java/com/easycrm/iam/RefreshTokenRepository.java`
- Modify: `backend/src/main/java/com/easycrm/iam/RefreshTokenService.java`

**Interfaces:**
- Consumes: Task 5's `rotate(String, Instant)` and `revokeIfLive`.
- Produces: `RefreshTokenService.GRACE = Duration.ofSeconds(30)`; repository methods `findGraceSuccessor`, `revokeByIdIfLive`, `markGraceUsed`.

- [ ] **Step 1: Write the failing grace tests**

```java
package com.easycrm.iam;

import static org.junit.jupiter.api.Assertions.*;

import com.easycrm.platform.error.UnauthorizedException;
import com.easycrm.support.IntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The lost-ACK case: the server commits a rotation, the response (and its Set-Cookie) never reaches
 * the browser, and the browser presents the now-revoked token again. Every tab shares that cookie, so
 * without grace the user is signed out everywhere. Spec 2026-09-14-f0 F0-5 and §3.3.
 */
class RefreshTokenGraceTest extends IntegrationTest {

    @Autowired
    RefreshTokenService refreshTokens;

    @Autowired
    TokenHasher hasher;

    private final Instant t0 = Instant.now();

    private record Owner(UUID userId, UUID tenantId, String raw) {}

    private Owner issue() {
        UUID u = UUID.randomUUID();
        UUID t = UUID.randomUUID();
        return new Owner(u, t, refreshTokens.issue(u, t));
    }

    @Test
    void aTokenWhoseResponseWasLostCanBePresentedOnceMore() throws Exception {
        Owner o = issue();
        var lost = refreshTokens.rotate(o.raw(), t0); // response never arrives

        var recovered = refreshTokens.rotate(o.raw(), t0.plusSeconds(10));

        assertNotEquals(lost.newRawToken(), recovered.newRawToken());
        assertThrows(UnauthorizedException.class, () -> refreshTokens.rotate(lost.newRawToken(), t0.plusSeconds(11)),
                "the orphaned successor must be revoked");
        assertThrows(UnauthorizedException.class, () -> refreshTokens.rotate(o.raw(), t0.plusSeconds(12)),
                "grace is single-use");
        assertEquals(1, liveTokensFor(o.userId()));
    }

    @Test
    void noGraceAfterThirtySeconds() {
        Owner o = issue();
        refreshTokens.rotate(o.raw(), t0);
        assertThrows(UnauthorizedException.class, () -> refreshTokens.rotate(o.raw(), t0.plusSeconds(31)));
    }

    @Test
    void noGraceOnceTheSuccessorHasBeenUsed() {
        // The client DID receive the successor and used it: the old token is a replay, not a lost ACK.
        Owner o = issue();
        var first = refreshTokens.rotate(o.raw(), t0);
        refreshTokens.rotate(first.newRawToken(), t0.plusSeconds(1));
        assertThrows(UnauthorizedException.class, () -> refreshTokens.rotate(o.raw(), t0.plusSeconds(2)));
    }

    @Test
    void noGraceForALoggedOutToken() {
        Owner o = issue();
        refreshTokens.revoke(o.raw());
        assertThrows(UnauthorizedException.class, () -> refreshTokens.rotate(o.raw(), t0.plusSeconds(1)));
    }

    @Test
    void aRealConcurrentRotationLeavesExactlyOneLiveToken() throws Exception {
        Owner o = issue();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection winner = ownerConnection()) {
            winner.setAutoCommit(false);
            UUID successorId = UUID.randomUUID();
            try (PreparedStatement ins = winner.prepareStatement(
                    "INSERT INTO refresh_token (id, token_hash, user_id, tenant_id, expires_at, version) "
                            + "VALUES (?, ?, ?, ?, now() + interval '30 days', 0)")) {
                ins.setObject(1, successorId);
                ins.setString(2, hasher.sha256Hex("winner-" + successorId));
                ins.setObject(3, o.userId());
                ins.setObject(4, o.tenantId());
                ins.executeUpdate();
            }
            try (PreparedStatement upd = winner.prepareStatement(
                    "UPDATE refresh_token SET revoked_at = now(), replaced_by_id = ?, version = version + 1 WHERE token_hash = ?")) {
                upd.setObject(1, successorId);
                upd.setString(2, hasher.sha256Hex(o.raw()));
                upd.executeUpdate();
            }

            Future<Throwable> loser = pool.submit(() -> {
                try {
                    refreshTokens.rotate(o.raw());
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            awaitABlockedStatement(Duration.ofSeconds(10));
            winner.commit();

            assertNull(loser.get(10, TimeUnit.SECONDS), "the loser recovers through grace");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, liveTokensFor(o.userId()), "never two live tokens for one session");
    }

    private static void awaitABlockedStatement(Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        try (Connection c = ownerConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query ILIKE '%refresh_token%'")) {
            while (Instant.now().isBefore(deadline)) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getLong(1) > 0) return;
                }
                Thread.sleep(25);
            }
        }
        fail("rotate() never blocked; the race was not forced");
    }

    private static long liveTokensFor(UUID userId) throws Exception {
        try (Connection c = ownerConnection();
                PreparedStatement ps = c.prepareStatement(
                        "SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL")) {
            ps.setObject(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
```

- [ ] **Step 2: Run and confirm failures**

Run: `./gradlew test --tests 'com.easycrm.iam.RefreshTokenGraceTest'`
Expected: `aTokenWhoseResponseWasLostCanBePresentedOnceMore` and `aRealConcurrentRotationLeavesExactlyOneLiveToken` FAIL with `UnauthorizedException`; the three "no grace" tests PASS already (they are the fences; they must stay green after Step 5).

- [ ] **Step 3: Migration**

`V35__refresh_token_grace.sql`:
```sql
-- Lost-response grace for refresh rotation (spec 2026-09-14-f0 §3.3).
-- When a rotation commits but its response never reaches the browser, the browser presents the
-- just-revoked token again. grace_used_at records that the single permitted re-presentation has
-- happened. refresh_token is a GLOBAL, RLS-exempt table (V7), so no per-tenant DML concern applies.
ALTER TABLE refresh_token ADD COLUMN grace_used_at TIMESTAMPTZ;
```

- [ ] **Step 4: Entity and repository**

`RefreshToken.java` — add after `replacedById`:
```java
    @Column(name = "grace_used_at")
    private Instant graceUsedAt;
```
and a getter `public Instant getGraceUsedAt() { return graceUsedAt; }`.

`RefreshTokenRepository.java` — add (`java.util.Optional` already imported):
```java
    /**
     * The orphaned successor of a token eligible for grace, locking the presented row so two grace
     * attempts serialize. Empty when the token is not recently rotated, already used its grace, is
     * expired, or was revoked by logout (replaced_by_id null).
     */
    @Query(value = """
            SELECT replaced_by_id FROM refresh_token
             WHERE token_hash = :hash
               AND revoked_at > :cutoff
               AND grace_used_at IS NULL
               AND expires_at > :now
               AND replaced_by_id IS NOT NULL
             FOR UPDATE
            """, nativeQuery = true)
    Optional<UUID> findGraceSuccessor(@Param("hash") String hash, @Param("cutoff") Instant cutoff, @Param("now") Instant now);

    /** Revokes one token only if nobody has used it yet. Zero rows means the successor was used. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE refresh_token SET revoked_at = :now, version = version + 1, updated_at = :now
             WHERE id = :id AND revoked_at IS NULL
            """, nativeQuery = true)
    int revokeByIdIfLive(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE refresh_token
               SET grace_used_at = :now, replaced_by_id = :successorId, version = version + 1, updated_at = :now
             WHERE token_hash = :hash AND grace_used_at IS NULL
            """, nativeQuery = true)
    int markGraceUsed(@Param("hash") String hash, @Param("successorId") UUID successorId, @Param("now") Instant now);
```

- [ ] **Step 5: Grace path in `rotate(String, Instant)`**

Add the constant `public static final Duration GRACE = Duration.ofSeconds(30);` (import `java.time.Duration`) and replace the final `throw invalid();` with:
```java
        // Lost-ACK grace (spec §3.3): the presented token was revoked by a rotation whose response
        // may never have arrived. Recover exactly once, within GRACE, and only if the successor that
        // rotation minted is still unused — a used successor means this is a replay, not a lost reply.
        UUID orphan = tokens.findGraceSuccessor(hash, now.minus(GRACE), now).orElseThrow(RefreshTokenService::invalid);
        if (tokens.revokeByIdIfLive(orphan, now) != 1) {
            throw invalid();
        }
        if (tokens.markGraceUsed(hash, successor.getId(), now) != 1) {
            throw invalid();
        }
        return new RotationResult(newRaw, userId, tenantId);
```

- [ ] **Step 6: Run the rotation tests**

If Spring Data cannot convert the native scalar to `Optional<UUID>` (a conversion error naming `UUID`), change the select to `SELECT CAST(replaced_by_id AS text)`, the return type to `Optional<String>`, and map with `.map(UUID::fromString)` in the service.

Run: `./gradlew test --tests 'com.easycrm.iam.RefreshToken*' --tests 'com.easycrm.iam.AuthServiceRefreshTest' --tests 'com.easycrm.arch.TenantScopingArchTest' --tests '*RlsCoverage*'`
Expected: PASS — all five grace tests and the Task 5 race test (its successor id does not exist, so `revokeByIdIfLive` matches zero and it still gets 401).

- [ ] **Step 7: Lint the migration and commit**

```bash
cd .. && npx --yes -p squawk-cli@2.65.0 squawk -c backend/squawk.toml backend/src/main/resources/db/migration/V35__refresh_token_grace.sql; cd backend
git add src/main/resources/db/migration/V35__refresh_token_grace.sql src/main/java/com/easycrm/iam/ src/test/java/com/easycrm/iam/RefreshTokenGraceTest.java
git commit -m "feat(auth): recover a lost refresh response once within 30 seconds

A rotation whose response is lost leaves every tab holding a revoked
cookie. The presented token may now be re-presented once within 30s,
only while its successor is unused; the orphaned successor is revoked.
A stolen token is replayable only inside that window and only before the
legitimate client uses its successor."
```
Expected: squawk exits 0 (a nullable `ADD COLUMN` is safe). If squawk flags it, read the rule; do not add the file to `excluded_paths`.

---

### Task 7: The refresh token travels in a cookie; session responses carry identity

**Files:**
- Create: `backend/src/main/java/com/easycrm/iam/IssuedSession.java`
- Create: `backend/src/main/java/com/easycrm/iam/web/RefreshCookie.java`
- Create: `backend/src/test/java/com/easycrm/iam/web/AuthCookieTest.java`
- Modify: `backend/src/main/java/com/easycrm/iam/web/dto/AuthResponse.java`
- Delete: `backend/src/main/java/com/easycrm/iam/web/dto/TokenResponse.java`, `RefreshRequest.java`
- Modify: `backend/src/main/java/com/easycrm/iam/AuthService.java` (signup, login, refresh)
- Modify: `backend/src/main/java/com/easycrm/iam/InvitationService.java` (accept)
- Modify: `backend/src/main/java/com/easycrm/iam/web/AuthController.java`
- Modify: `backend/src/main/java/com/easycrm/iam/web/PublicInvitationController.java`
- Modify: `backend/src/main/java/com/easycrm/platform/openapi/OpenApiConfig.java` (description)
- Modify tests: `AuthControllerTest`, `AuthServiceRefreshTest`, `AuthServiceSignupTest`, `AuthServiceLoginTest`, `InvitationAcceptTest`, `InvitationControllerTest`
- Regenerate: `docs/api/openapi.yaml`

**Interfaces:**
- Produces:
  - `record AuthResponse(String accessToken, UUID userId, UUID tenantId, String tenantSlug, String email, String role)`.
  - `record IssuedSession(AuthResponse body, String refreshToken)` with `accessToken()`, `userId()`, `tenantId()`, `role()` delegates.
  - `AuthService.signup(SignupRequest) : IssuedSession`, `login(LoginRequest) : IssuedSession`, `refresh(String) : IssuedSession`, `logout(String) : void`.
  - `InvitationService.accept(String, AcceptInvitationRequest) : IssuedSession`.
  - `RefreshCookie` bean: `NAME = "easycrm_rt"`, `write(HttpServletResponse, String)`, `clear(HttpServletResponse)`, `read(HttpServletRequest) : Optional<String>`.
  - HTTP: signup/login/accept/refresh set `easycrm_rt`; refresh/logout read it; no request or response body carries a refresh token.

- [ ] **Step 1: Write the failing cookie tests**

```java
package com.easycrm.iam.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.easycrm.platform.tenancy.TenantContext;
import com.easycrm.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Spec 2026-09-14-f0 §3.1: the refresh token lives only in an httpOnly cookie. */
@SpringBootTest
@AutoConfigureMockMvc
class AuthCookieTest extends IntegrationTest {

    static final String CLIENT = "X-EasyCRM-Client";

    @Autowired
    MockMvc mvc;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    static String setCookie(MvcResult r) {
        List<String> all = r.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        return all.stream().filter(h -> h.startsWith("easycrm_rt=")).findFirst()
                .orElseThrow(() -> new AssertionError("no easycrm_rt Set-Cookie in " + all));
    }

    static String cookieValue(MvcResult r) {
        String h = setCookie(r);
        return h.substring("easycrm_rt=".length(), h.indexOf(';'));
    }

    static void assertSessionCookie(MvcResult r) {
        String h = setCookie(r);
        assertTrue(h.contains("Path=/api/v1/auth"), h);
        assertTrue(h.contains("Max-Age=2592000"), h);
        assertTrue(h.contains("Secure"), h);
        assertTrue(h.contains("HttpOnly"), h);
        assertTrue(h.contains("SameSite=Strict"), h);
        assertFalse(cookieValue(r).isBlank(), h);
    }

    MvcResult signup(String slug) throws Exception {
        return mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"slug":"%s","businessName":"Cookie Biz","stateCode":"27",
                             "email":"Owner@%s.test","password":"correct-horse"}""".formatted(slug, slug)))
                .andExpect(status().isCreated())
                .andReturn();
    }

    @Test
    void signupSetsTheCookieAndReturnsIdentityWithoutARefreshToken() throws Exception {
        String slug = "ck-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult r = signup(slug);

        assertSessionCookie(r);
        String body = r.getResponse().getContentAsString();
        assertFalse(body.contains("refreshToken"), body);
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("easycrm_rt", cookieValue(r))).header(CLIENT, "web"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.tenantSlug").value(slug))
                .andExpect(jsonPath("$.email").value("Owner@" + slug + ".test"))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
    }

    @Test
    void loginSetsTheCookie() throws Exception {
        String slug = "ck-" + UUID.randomUUID().toString().substring(0, 8);
        signup(slug);
        MvcResult r = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"slug":"%s","email":"owner@%s.test","password":"correct-horse"}""".formatted(slug, slug)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantSlug").value(slug))
                .andReturn();
        assertSessionCookie(r);
    }

    @Test
    void refreshRotatesTheCookie() throws Exception {
        MvcResult s = signup("ck-" + UUID.randomUUID().toString().substring(0, 8));
        String first = cookieValue(s);

        MvcResult r = mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("easycrm_rt", first)).header(CLIENT, "web"))
                .andExpect(status().isOk())
                .andReturn();

        assertSessionCookie(r);
        assertNotEquals(first, cookieValue(r));
    }

    @Test
    void refreshWithoutACookieIs401() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").header(CLIENT, "web")).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesAndClearsTheCookie() throws Exception {
        String raw = cookieValue(signup("ck-" + UUID.randomUUID().toString().substring(0, 8)));

        MvcResult r = mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("easycrm_rt", raw)).header(CLIENT, "web"))
                .andExpect(status().isNoContent())
                .andReturn();

        String cleared = setCookie(r);
        assertTrue(cleared.contains("Max-Age=0"), cleared);
        assertTrue(cleared.contains("Path=/api/v1/auth"), cleared);
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("easycrm_rt", raw)).header(CLIENT, "web"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutWithoutACookieIsStill204() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").header(CLIENT, "web")).andExpect(status().isNoContent());
    }
}
```

- [ ] **Step 2: Run and confirm failures**

Run: `./gradlew test --tests 'com.easycrm.iam.web.AuthCookieTest'`
Expected: FAIL — `no easycrm_rt Set-Cookie`, and refresh/logout without a JSON body return 400.

- [ ] **Step 3: DTO and internal session type**

`AuthResponse.java`:
```java
package com.easycrm.iam.web.dto;

import java.util.UUID;

/**
 * Every session-issuing response (signup, login, invitation accept, refresh). Carries the same
 * identity as MeResponse so a browser needs no follow-up /me call and can detect a principal change
 * on refresh. The refresh token is NOT here: it travels only in the easycrm_rt httpOnly cookie
 * (spec 2026-09-14-f0 §3.1).
 */
public record AuthResponse(String accessToken, UUID userId, UUID tenantId, String tenantSlug, String email, String role) {}
```
Delete `TokenResponse.java` and `RefreshRequest.java`.

`IssuedSession.java`:
```java
package com.easycrm.iam;

import com.easycrm.iam.web.dto.AuthResponse;
import java.util.UUID;

/**
 * What a session-issuing service call produces: the response body, plus the raw refresh token that
 * only the web layer may see, and only to put in a cookie. Keeping the token out of AuthResponse
 * makes "a refresh token in a JSON body" unrepresentable rather than merely unlikely.
 */
public record IssuedSession(AuthResponse body, String refreshToken) {

    public String accessToken() {
        return body.accessToken();
    }

    public UUID userId() {
        return body.userId();
    }

    public UUID tenantId() {
        return body.tenantId();
    }

    public String role() {
        return body.role();
    }
}
```

- [ ] **Step 4: Services return `IssuedSession`**

`AuthService.signup` — replace the lambda's return:
```java
                String access = jwt.mint(tenant.getId(), owner.getId(), Role.OWNER.name());
                String refresh = refreshTokens.issue(owner.getId(), tenant.getId());
                return new IssuedSession(
                        new AuthResponse(access, owner.getId(), tenant.getId(), tenant.getSlug(), owner.getEmail(), Role.OWNER.name()),
                        refresh);
```
and change the method signature and the `AuthResponse res = tx.execute(...)` local to `IssuedSession`.

`AuthService.login` — return type `IssuedSession`, and:
```java
                String access = jwt.mint(tenant.getId(), user.getId(), user.getRole().name());
                String refresh = refreshTokens.issue(user.getId(), tenant.getId());
                return new IssuedSession(
                        new AuthResponse(access, user.getId(), tenant.getId(), tenant.getSlug(), user.getEmail(), user.getRole().name()),
                        refresh);
```

`AuthService.refresh`:
```java
    public IssuedSession refresh(String rawToken) {
        RefreshTokenService.RotationResult rot = refreshTokens.rotate(rawToken);
        TenantContext.set(new TenantContext.TenantPrincipal(rot.tenantId(), rot.userId(), "SYSTEM"));
        try {
            return tx.execute(status -> {
                User user = users.findById(rot.userId())
                        .orElseThrow(() -> new UnauthorizedException("invalid refresh token"));
                // (keep the existing disabled-member comment)
                if (user.getStatus() != UserStatus.ACTIVE) {
                    throw new UnauthorizedException("invalid refresh token");
                }
                Tenant tenant = tenants.findById(rot.tenantId())
                        .orElseThrow(() -> new UnauthorizedException("invalid refresh token"));
                String access = jwt.mint(rot.tenantId(), rot.userId(), user.getRole().name());
                return new IssuedSession(
                        new AuthResponse(access, user.getId(), tenant.getId(), tenant.getSlug(), user.getEmail(), user.getRole().name()),
                        rot.newRawToken());
            });
        } finally {
            TenantContext.clear();
        }
    }
```
Remove the `TokenResponse` import.

`InvitationService.accept` — keep the `Live` value so the tenant is available, change the return type to `IssuedSession`:
```java
    public IssuedSession accept(String rawToken, AcceptInvitationRequest req) {
        Live live = requireLive(rawToken);
        Invitation inv = live.invitation();
        ...
                String access = jwt.mint(claimed.getTenantId(), user.getId(), claimed.getRole().name());
                String refresh = refreshTokens.issue(user.getId(), claimed.getTenantId());
                return new IssuedSession(
                        new AuthResponse(
                                access,
                                user.getId(),
                                claimed.getTenantId(),
                                live.tenant().getSlug(),
                                user.getEmail(),
                                claimed.getRole().name()),
                        refresh);
```

- [ ] **Step 5: `RefreshCookie`**

```java
package com.easycrm.iam.web;

import com.easycrm.iam.RefreshTokenService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The only code that builds, reads or clears the refresh cookie (spec 2026-09-14-f0 §3.1, guarded by
 * AuthSessionBoundaryArchTest).
 *
 * <p>Path-scoped to /api/v1/auth rather than __Host-prefixed: __Host- forces Path=/, which would send
 * the refresh token with every API call. Secure is unconditional; Chromium accepts Secure cookies on
 * http://localhost, which is the supported dev and E2E browser.
 */
@Component
public class RefreshCookie {

    public static final String NAME = "easycrm_rt";
    static final String PATH = "/api/v1/auth";
    static final Duration MAX_AGE = Duration.ofDays(RefreshTokenService.TTL_DAYS);

    public void write(HttpServletResponse response, String rawToken) {
        response.addHeader(HttpHeaders.SET_COOKIE, build(rawToken, MAX_AGE).toString());
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, build("", Duration.ZERO).toString());
    }

    public Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return Optional.empty();
        return Arrays.stream(cookies)
                .filter(c -> NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }

    private static ResponseCookie build(String value, Duration maxAge) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path(PATH)
                .maxAge(maxAge)
                .build();
    }
}
```

- [ ] **Step 6: Controllers**

`AuthController.java`:
```java
package com.easycrm.iam.web;

import com.easycrm.iam.AuthService;
import com.easycrm.iam.IssuedSession;
import com.easycrm.iam.web.dto.AuthResponse;
import com.easycrm.iam.web.dto.LoginRequest;
import com.easycrm.iam.web.dto.MeResponse;
import com.easycrm.iam.web.dto.SignupRequest;
import com.easycrm.platform.error.UnauthorizedException;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;
    private final RefreshCookie cookie;

    public AuthController(AuthService auth, RefreshCookie cookie) {
        this.auth = auth;
        this.cookie = cookie;
    }

    // (keep the existing @SecurityRequirements comment)
    @SecurityRequirements
    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest req, HttpServletResponse response) {
        return ResponseEntity.status(HttpStatus.CREATED).body(issue(auth.signup(req), response));
    }

    @SecurityRequirements
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req, HttpServletResponse response) {
        return issue(auth.login(req), response);
    }

    @SecurityRequirements
    @PostMapping("/refresh")
    public AuthResponse refresh(HttpServletRequest request, HttpServletResponse response) {
        String raw = cookie.read(request).orElseThrow(() -> new UnauthorizedException("invalid refresh token"));
        return issue(auth.refresh(raw), response);
    }

    @SecurityRequirements
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        cookie.read(request).ifPresent(auth::logout);
        cookie.clear(response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public MeResponse me() {
        return auth.me();
    }

    private AuthResponse issue(IssuedSession session, HttpServletResponse response) {
        cookie.write(response, session.refreshToken());
        return session.body();
    }
}
```

`PublicInvitationController.java` — constructor takes `RefreshCookie cookie` too; `accept` becomes:
```java
    @SecurityRequirements
    @PostMapping("/{token}/accept")
    public ResponseEntity<AuthResponse> accept(
            @PathVariable String token, @Valid @RequestBody AcceptInvitationRequest req, HttpServletResponse response) {
        IssuedSession session = invitations.accept(token, req);
        cookie.write(response, session.refreshToken());
        return ResponseEntity.status(HttpStatus.CREATED).body(session.body());
    }
```
(imports: `com.easycrm.iam.IssuedSession`, `jakarta.servlet.http.HttpServletResponse`)

`OpenApiConfig.java` description — replace the sentence `Errors share one envelope: {"error":{"code","message","fields"}}.` with:
```
                                Money is carried as a JSON string, never a number. Errors share one \
                                envelope: {"error":{"code","message","fields","fieldCodes"}}; clients \
                                translate fieldCodes (docs/api/error-codes.md). The refresh token is never \
                                in a body: signup, login, invitation accept and refresh set it as the \
                                httpOnly easycrm_rt cookie, and refresh and logout read it.\
```
(Keep the preceding sentences; make sure "Money is carried…" appears once.)

- [ ] **Step 7: Update existing tests to the new types**

- `AuthServiceRefreshTest`: replace `AuthResponse` with `IssuedSession` (import `com.easycrm.iam.IssuedSession` is same package — just rename the type), replace `TokenResponse res` with `IssuedSession res`; remove the `TokenResponse` and `AuthResponse` imports.
- `AuthServiceSignupTest`: `AuthResponse res` → `IssuedSession res`; line 46 `assertNotNull(res.refreshToken());` stays valid. Remove the `AuthResponse` import.
- `AuthServiceLoginTest`: `AuthResponse` → `IssuedSession` (helper return type and locals); remove the import.
- `InvitationControllerTest:134` uses `var` — no change.
- `InvitationAcceptTest:64`: replace `.andExpect(jsonPath("$.refreshToken").exists())` with:
```java
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.email").value("new@shop.in"))
                .andExpect(jsonPath("$.tenantSlug").exists())
                .andExpect(header().stringValues(org.springframework.http.HttpHeaders.SET_COOKIE,
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.startsWith("easycrm_rt="))))
```
- `AuthControllerTest.signupThenLoginThenRefresh`: replace the refresh block (lines 50–56) with a cookie-based refresh:
```java
        String raw = AuthCookieTest.cookieValue(signupResult);
        mvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("easycrm_rt", raw))
                        .header("X-EasyCRM-Client", "web"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists());
```
where `signupResult` is the `MvcResult` from the signup call (change `.andReturn().getResponse().getContentAsString()` to `.andReturn()` and drop the `body`/`JsonPath` usage).

- [ ] **Step 8: Run the iam and security tests**

Run: `./gradlew test --tests 'com.easycrm.iam.*' --tests 'com.easycrm.platform.security.*'`
Expected: PASS. Fix any remaining compile errors by following the type changes above; do not reintroduce a refresh token into any body.

- [ ] **Step 9: Regenerate the contract, full check, commit**

```bash
./gradlew updateOpenApiSnapshot
grep -n "refreshToken\|RefreshRequest\|TokenResponse" ../docs/api/openapi.yaml   # expect no output
./gradlew check
git add -A src ../docs/api/openapi.yaml
git commit -m "feat(auth): move the refresh token into an httpOnly cookie

Signup, login, invitation accept and refresh set easycrm_rt (HttpOnly,
Secure, SameSite=Strict, Path=/api/v1/auth); refresh and logout read it.
No body carries a refresh token any more. Every session response carries
the caller's identity (userId, tenantId, tenantSlug, email, role), so a
browser boots in one request and can detect a principal change."
```

---

### Task 8: Refresh and logout require the client header

**Files:**
- Modify: `backend/src/main/java/com/easycrm/iam/web/AuthController.java`
- Test: `backend/src/test/java/com/easycrm/iam/web/AuthCookieTest.java`

**Interfaces:** Produces `AuthController.CLIENT_HEADER = "X-EasyCRM-Client"`; missing/other value → `ForbiddenException` → 403 envelope with `code: FORBIDDEN`.

- [ ] **Step 1: Write the failing tests**

Append to `AuthCookieTest.java` (add import `static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options`):
```java
    @Test
    void refreshWithoutTheClientHeaderIs403WithTheEnvelope() throws Exception {
        String raw = cookieValue(signup("ck-" + UUID.randomUUID().toString().substring(0, 8)));
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("easycrm_rt", raw)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        // The header check must run BEFORE rotation: the token is still usable afterwards.
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("easycrm_rt", raw)).header(CLIENT, "web"))
                .andExpect(status().isOk());
    }

    @Test
    void logoutWithoutTheClientHeaderIs403AndRevokesNothing() throws Exception {
        String raw = cookieValue(signup("ck-" + UUID.randomUUID().toString().substring(0, 8)));
        mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("easycrm_rt", raw)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("easycrm_rt", raw)).header(CLIENT, "web"))
                .andExpect(status().isOk());
    }

    @Test
    void aCrossOriginPreflightToRefreshIsRefused() throws Exception {
        // No CORS configuration exists, so a cross-origin fetch carrying the custom header can never
        // pass its preflight. If CORS is ever added, this test forces a deliberate decision.
        MvcResult r = mvc.perform(options("/api/v1/auth/refresh")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, CLIENT))
                .andReturn();
        assertNull(r.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertTrue(r.getResponse().getStatus() >= 400, "preflight status " + r.getResponse().getStatus());
    }
```

- [ ] **Step 2: Run and confirm failures**

Run: `./gradlew test --tests 'com.easycrm.iam.web.AuthCookieTest'`
Expected: the two header tests FAIL (200/204 instead of 403). The preflight test PASSES already — record that; it is a regression fence, and Step 5 proves it can fail.

- [ ] **Step 3: Implement the check**

In `AuthController` add (import `com.easycrm.platform.error.ForbiddenException`):
```java
    /**
     * CSRF defence-in-depth on the two routes that authenticate with the cookie (spec §3.2). A
     * cross-site form cannot set a custom header, and a cross-origin fetch that sets one fails its
     * preflight. Explicit code rather than @RequestHeader, which would answer 400 instead of 403.
     */
    static final String CLIENT_HEADER = "X-EasyCRM-Client";

    private static void requireWebClient(HttpServletRequest request) {
        if (!"web".equals(request.getHeader(CLIENT_HEADER))) {
            throw new ForbiddenException("missing client header");
        }
    }
```
Call `requireWebClient(request);` as the **first** line of `refresh(...)` and `logout(...)`.

- [ ] **Step 4: Run**

Run: `./gradlew test --tests 'com.easycrm.iam.web.*'`
Expected: PASS.

- [ ] **Step 5: Prove the preflight fence can fail, then revert**

Temporarily add to `SecurityConfig.filterChain`, before `.authorizeHttpRequests`:
```java
                .cors(c -> c.configurationSource(req -> {
                    var cfg = new org.springframework.web.cors.CorsConfiguration();
                    cfg.addAllowedOriginPattern("*");
                    cfg.addAllowedMethod("*");
                    cfg.addAllowedHeader("*");
                    return cfg;
                }))
```
Run: `./gradlew test --tests 'com.easycrm.iam.web.AuthCookieTest.aCrossOriginPreflightToRefreshIsRefused'`
Expected: FAIL. Then `git checkout src/main/java/com/easycrm/platform/security/SecurityConfig.java` and re-run: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/easycrm/iam/web/AuthController.java src/test/java/com/easycrm/iam/web/AuthCookieTest.java
git commit -m "feat(auth): require X-EasyCRM-Client on refresh and logout

CSRF defence-in-depth on the two cookie-authenticated routes, checked
before any token is touched. A preflight test (seen failing with a
permissive CORS config) pins that no cross-origin caller can send it."
```

---

### Task 9: Login and accept revoke a stale incoming cookie

**Files:**
- Modify: `backend/src/main/java/com/easycrm/iam/web/AuthController.java` (login)
- Modify: `backend/src/main/java/com/easycrm/iam/web/PublicInvitationController.java` (accept)
- Test: `backend/src/test/java/com/easycrm/iam/web/AuthCookieTest.java`

**Interfaces:** Consumes `RefreshCookie.read`, `AuthService.logout(String)`. `PublicInvitationController` constructor gains `AuthService auth`.

- [ ] **Step 1: Write the failing tests**

Append to `AuthCookieTest.java` (add `@Autowired com.easycrm.support.TestTokens tokens;`):
```java
    @Test
    void loggingInAsSomeoneElseRevokesTheCookieTheBrowserSent() throws Exception {
        String stale = cookieValue(signup("ck-" + UUID.randomUUID().toString().substring(0, 8)));
        String slugB = "ck-" + UUID.randomUUID().toString().substring(0, 8);
        signup(slugB);

        mvc.perform(post("/api/v1/auth/login")
                        .cookie(new Cookie("easycrm_rt", stale))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"slug":"%s","email":"owner@%s.test","password":"correct-horse"}""".formatted(slugB, slugB)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("easycrm_rt", stale)).header(CLIENT, "web"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptingAnInvitationRevokesTheCookieTheBrowserSent() throws Exception {
        String stale = cookieValue(signup("ck-" + UUID.randomUUID().toString().substring(0, 8)));
        var owner = tokens.provisionOwner("27");
        String inviteBody = mvc.perform(post("/api/v1/invitations")
                        .header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"stale-" + UUID.randomUUID() + "@shop.in\",\"role\":\"SALES_EXEC\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String acceptUrl = com.jayway.jsonpath.JsonPath.read(inviteBody, "$.acceptUrl");
        String token = acceptUrl.substring(acceptUrl.lastIndexOf('/') + 1);

        mvc.perform(post("/api/v1/auth/invitations/" + token + "/accept")
                        .cookie(new Cookie("easycrm_rt", stale))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"correct-horse\"}"))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("easycrm_rt", stale)).header(CLIENT, "web"))
                .andExpect(status().isUnauthorized());
    }
```

- [ ] **Step 2: Run and confirm failure**

Run: `./gradlew test --tests 'com.easycrm.iam.web.AuthCookieTest'`
Expected: both new tests FAIL (the stale token still refreshes: 200).

- [ ] **Step 3: Implement**

`AuthController.login`:
```java
    @SecurityRequirements
    @PostMapping("/login")
    public AuthResponse login(
            @Valid @RequestBody LoginRequest req, HttpServletRequest request, HttpServletResponse response) {
        IssuedSession session = auth.login(req);
        // Switching users on a device must not leave the previous refresh token live for 30 days.
        // Revoked only after a SUCCESSFUL login, and never used to authenticate (spec §3.1).
        cookie.read(request).ifPresent(auth::logout);
        return issue(session, response);
    }
```
`PublicInvitationController` — inject `AuthService auth` and:
```java
    public ResponseEntity<AuthResponse> accept(
            @PathVariable String token,
            @Valid @RequestBody AcceptInvitationRequest req,
            HttpServletRequest request,
            HttpServletResponse response) {
        IssuedSession session = invitations.accept(token, req);
        cookie.read(request).ifPresent(auth::logout); // same reason as AuthController.login
        cookie.write(response, session.refreshToken());
        return ResponseEntity.status(HttpStatus.CREATED).body(session.body());
    }
```

- [ ] **Step 4: Run**

Run: `./gradlew test --tests 'com.easycrm.iam.web.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/easycrm/iam/web/ src/test/java/com/easycrm/iam/web/AuthCookieTest.java
git commit -m "feat(auth): revoke the previous session's cookie on login and accept

A shared device switching users would otherwise keep the old refresh token
live for 30 days. Revoked only after the new session is issued."
```

---

### Task 10: Session resumption gets its own rate-limit bucket

**Files:**
- Modify: `backend/src/main/resources/application.yml` (`easycrm.rate-limit.policies`)
- Test: `backend/src/test/java/com/easycrm/platform/ratelimit/RateLimitDefaultsTest.java`

**Interfaces:** Produces policy `session` (120 per 1m) matched for `/api/v1/auth/refresh`, `/api/v1/auth/logout`, `/api/v1/auth/me`.

- [ ] **Step 1: Write the failing test**

Append to `RateLimitDefaultsTest.java`:
```java
    @Test
    void sessionResumptionHasItsOwnLooserBucketAndCredentialRoutesKeepTheStrictOne() throws Exception {
        RateLimitProperties props = shippedProperties();

        for (String path : java.util.List.of("/api/v1/auth/refresh", "/api/v1/auth/logout", "/api/v1/auth/me")) {
            RateLimitPolicy p = props.policyFor(path).orElseThrow(() -> new AssertionError(path + " is unprotected"));
            assertEquals("session", p.name(), path + " must be governed by session, not auth (policy ORDER decides)");
            assertEquals(120, p.capacity());
            assertEquals(Duration.ofMinutes(1), p.refillPeriod());
        }
        for (String path : java.util.List.of(
                "/api/v1/auth/login",
                "/api/v1/auth/signup",
                "/api/v1/auth/signup/status",
                "/api/v1/auth/invitations/abc",
                "/api/v1/auth/invitations/abc/accept")) {
            assertEquals("auth", props.policyFor(path).orElseThrow().name(), path);
        }
    }
```

- [ ] **Step 2: Run**

Run: `./gradlew test --tests 'com.easycrm.platform.ratelimit.RateLimitDefaultsTest'`
Expected: FAIL — `expected: <session> but was: <auth>`.

- [ ] **Step 3: Configure**

In `application.yml`, insert **between** the `public-share` policy and the `auth` policy:
```yaml
      # Session resumption. Every tab boot and every 15-minute access-token expiry hits these, and an
      # office sits behind one NAT IP, so they cannot share the credential-stuffing bucket below:
      # starving refresh signs people out. MUST stay above `auth` -- policyFor is first match.
      - name: session
        path: /api/v1/auth/{endpoint:refresh|logout|me}
        capacity: 120
        refill-period: 1m
```
and replace the `auth` policy's comment with:
```yaml
      # Credential stuffing: login, signup, invitation preview/accept. Session resumption has its own
      # bucket above, so this only has to fit real humans typing passwords.
```

- [ ] **Step 4: Run, and confirm the pattern actually parses**

Run: `./gradlew test --tests 'com.easycrm.platform.ratelimit.*'`
Expected: PASS. If `RateLimitPolicyTest`/binding fails on the `{endpoint:refresh|logout|me}` syntax, replace the single policy with three policies named `session`, identical except `path` (`/api/v1/auth/refresh`, `/api/v1/auth/logout`, `/api/v1/auth/me`) — but check `InMemoryRateLimitStore` namespaces buckets by policy **name**; if it does, three same-named policies share one bucket, which is the intent.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/application.yml src/test/java/com/easycrm/platform/ratelimit/RateLimitDefaultsTest.java
git commit -m "feat(ratelimit): give refresh, logout and me their own session bucket

Every page load now resumes a session through these routes, and an office
shares one IP; they no longer drain the 30/min credential bucket."
```

---

### Task 11: Signup switch and status route

**Files:**
- Create: `backend/src/main/java/com/easycrm/iam/SignupProperties.java`
- Create: `backend/src/main/java/com/easycrm/iam/web/dto/SignupStatusResponse.java`
- Create: `backend/src/test/java/com/easycrm/iam/web/SignupClosedTest.java`
- Create: `backend/src/test/java/com/easycrm/iam/SignupDefaultsTest.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/com/easycrm/iam/AuthService.java`
- Modify: `backend/src/main/java/com/easycrm/iam/web/AuthController.java`
- Modify: `backend/src/main/java/com/easycrm/platform/security/SecurityConfig.java`
- Test: `backend/src/test/java/com/easycrm/iam/web/AuthCookieTest.java` (status open)
- Regenerate: `docs/api/openapi.yaml`

**Interfaces:**
- Produces: `record SignupProperties(boolean enabled)` bound to `easycrm.signup`; `AuthService.signupOpen() : boolean`; `GET /api/v1/auth/signup/status` → `SignupStatusResponse(boolean open)`.

- [ ] **Step 1: Write the failing tests**

`SignupDefaultsTest.java`:
```java
package com.easycrm.iam;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** The shipped default is OPEN in every environment (spec F0-3). Reads application.yml directly. */
class SignupDefaultsTest {

    @Test
    void signupShipsOpen() throws Exception {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
        StandardEnvironment env = new StandardEnvironment();
        sources.forEach(s -> env.getPropertySources().addLast(s));
        SignupProperties props = new Binder(ConfigurationPropertySources.get(env))
                .bind("easycrm.signup", SignupProperties.class)
                .orElseThrow(() -> new AssertionError("easycrm.signup is not configured in application.yml"));
        assertTrue(props.enabled());
    }
}
```
`SignupClosedTest.java`:
```java
package com.easycrm.iam.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.easycrm.support.IntegrationTest;
import com.easycrm.support.TestTokens;
import com.easycrm.tenant.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Signup closed (spec §3.6). Its own context: the property is read per request from a bound record. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "easycrm.signup.enabled=false")
class SignupClosedTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestTokens tokens;

    @Autowired
    TenantRepository tenants;

    private static final String BODY = """
        {"slug":"%s","businessName":"Closed","stateCode":"27","email":"o@closed.test","password":"correct-horse"}""";

    @Test
    void statusReportsClosed() throws Exception {
        mvc.perform(get("/api/v1/auth/signup/status")).andExpect(status().isOk()).andExpect(jsonPath("$.open").value(false));
    }

    @Test
    void aClosedSignupIs404AndRevealsNothingAboutSlugs() throws Exception {
        var existing = tokens.provisionOwner("27");
        String takenSlug = tenants.findById(existing.tenantId()).orElseThrow().getSlug();

        String taken = mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(BODY.formatted(takenSlug)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String free = mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(BODY.formatted("closed-free-slug")))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertEquals(free, taken, "a taken and a free slug must be indistinguishable while signup is closed");
    }
}
```
Append to `AuthCookieTest.java`:
```java
    @Test
    void statusReportsOpenByDefault() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/signup/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(true));
    }
```
(`tenants.findById(...)` on the global `tenant` table works without a tenant context? `TestTokens.suspend` binds one for `TenantAwareTransactionManager`. If the read throws for lack of context, wrap it: `TenantContext.set(new TenantContext.TenantPrincipal(existing.tenantId(), null, "SYSTEM"))` before and `TenantContext.clear()` after.)

- [ ] **Step 2: Run**

Run: `./gradlew test --tests 'com.easycrm.iam.SignupDefaultsTest' --tests 'com.easycrm.iam.web.SignupClosedTest' --tests 'com.easycrm.iam.web.AuthCookieTest.statusReportsOpenByDefault'`
Expected: compile FAIL (`SignupProperties` missing).

- [ ] **Step 3: Implement**

`SignupProperties.java`:
```java
package com.easycrm.iam;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The self-serve signup switch (spec 2026-09-14-f0 F0-3). Defaults OPEN; close with
 * SIGNUP_ENABLED=false and a restart. A live toggle waits on the platform admin role (ROADMAP 4a).
 */
@ConfigurationProperties("easycrm.signup")
public record SignupProperties(@DefaultValue("true") boolean enabled) {}
```
`SignupStatusResponse.java`:
```java
package com.easycrm.iam.web.dto;

public record SignupStatusResponse(boolean open) {}
```
`application.yml`, under `easycrm:` directly after `public-base-url`:
```yaml
  # Self-serve signup (spec 2026-09-14-f0 F0-3). Open by default everywhere; set SIGNUP_ENABLED=false
  # and restart to close it. Closed -> POST /auth/signup is 404, GET /auth/signup/status says so.
  signup:
    enabled: ${SIGNUP_ENABLED:true}
```
`AuthService` — add a `SignupProperties signup` constructor parameter and field, then:
```java
    public boolean signupOpen() {
        return signup.enabled();
    }
```
and make this the **first** statement of `signup(...)`:
```java
        // Before the slug lookup: a closed signup must not answer 409-vs-404 depending on whether the
        // slug exists (spec §3.6).
        if (!signup.enabled()) {
            throw new NotFoundException("signup is closed");
        }
```
(import `com.easycrm.platform.error.NotFoundException`)

`AuthController`:
```java
    @SecurityRequirements
    @GetMapping("/signup/status")
    public SignupStatusResponse signupStatus() {
        return new SignupStatusResponse(auth.signupOpen());
    }
```
`SecurityConfig`, after the invitation preview permit:
```java
                        // Whether self-serve signup is open. Reveals one boolean; governed by the auth
                        // rate-limit policy like the rest of the prefix.
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/signup/status")
                        .permitAll()
```
If any test constructs `AuthService` by hand (`grep -rn "new AuthService(" src/test`), pass `new SignupProperties(true)`.

- [ ] **Step 4: Run**

Run: `./gradlew test --tests 'com.easycrm.iam.*' --tests 'com.easycrm.platform.security.*'`
Expected: PASS.

- [ ] **Step 5: Regenerate, check, commit**

```bash
./gradlew updateOpenApiSnapshot
grep -n -A3 "/api/v1/auth/signup/status:" ../docs/api/openapi.yaml
./gradlew check
git add -A src ../docs/api/openapi.yaml
git commit -m "feat(auth): add a signup switch and a public signup status route

easycrm.signup.enabled (SIGNUP_ENABLED) defaults open everywhere. Closed,
signup is a 404 checked before the slug lookup, so it reveals nothing;
GET /api/v1/auth/signup/status tells the page before anyone fills the form."
```

---

### Task 12: Guard who may rotate tokens and touch the cookie

**Files:**
- Create: `backend/src/test/java/com/easycrm/arch/AuthSessionBoundaryArchTest.java`

**Interfaces:** Consumes the classes from Tasks 5–11.

- [ ] **Step 1: Write the guard**

```java
package com.easycrm.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.easycrm.iam.AuthService;
import com.easycrm.iam.RefreshTokenService;
import com.easycrm.iam.web.AuthController;
import com.easycrm.iam.web.PublicInvitationController;
import com.easycrm.iam.web.RefreshCookie;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Spec 2026-09-14-f0 §3.8. The refresh cookie authenticates exactly one operation — rotation, via
 * POST /auth/refresh — and only two controllers may handle the cookie at all.
 *
 * <p>Each assertion is an EQUALITY against the expected set, not a subset: it fails if a new caller
 * appears AND if the expected caller disappears, so it cannot pass by matching nothing.
 */
class AuthSessionBoundaryArchTest {

    private static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.easycrm");

    @Test
    void onlyAuthServiceRotatesRefreshTokens() {
        Set<String> callers = new TreeSet<>();
        MAIN.forEach(c -> c.getMethodCallsFromSelf().stream()
                .filter(call -> call.getTarget().getOwner().isEquivalentTo(RefreshTokenService.class))
                .filter(call -> call.getName().equals("rotate"))
                .forEach(call -> callers.add(call.getOriginOwner().getName())));
        callers.remove(RefreshTokenService.class.getName()); // rotate(raw) delegating to rotate(raw, now)

        assertThat(callers).containsExactly(AuthService.class.getName());
    }

    @Test
    void onlyTheTwoAuthControllersTouchTheRefreshCookie() {
        Set<String> users = new TreeSet<>();
        MAIN.forEach(c -> c.getDirectDependenciesFromSelf().stream()
                .filter(d -> d.getTargetClass().isEquivalentTo(RefreshCookie.class))
                .forEach(d -> users.add(d.getOriginClass().getName())));
        users.remove(RefreshCookie.class.getName());

        assertThat(users).containsExactlyInAnyOrder(AuthController.class.getName(), PublicInvitationController.class.getName());
    }
}
```

- [ ] **Step 2: Run it**

Run: `./gradlew test --tests 'com.easycrm.arch.AuthSessionBoundaryArchTest'`
Expected: PASS. If `getOriginOwner`/`getOriginClass` resolves nested lambda classes (e.g. `AuthController$...`), strip everything after `$` when adding to the set.

- [ ] **Step 3: Prove each rule can fail, then revert**

(a) In `MemberService` (or any iam service with a `RefreshTokenService` field), temporarily add a method `void probe() { refreshTokens.rotate("x"); }`. Run the test: `onlyAuthServiceRotatesRefreshTokens` FAILS naming `MemberService`. Revert with `git checkout`.
(b) Temporarily inject `RefreshCookie` into `InvitationController`'s constructor. Run: `onlyTheTwoAuthControllersTouchTheRefreshCookie` FAILS. Revert.
Re-run: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/easycrm/arch/AuthSessionBoundaryArchTest.java
git commit -m "test(arch): pin who may rotate refresh tokens and touch the cookie

Equality assertions, each seen failing with a planted violation, so the
rule fails both on a new caller and on the expected caller vanishing."
```

---

### Task 13: Documentation owed, and final verification

**Files:**
- Modify: `docs/superpowers/engineering-challenges.md` (append #80)
- Modify: `docs/superpowers/annotations-reference.md`
- Modify: `docs/superpowers/specs/2026-09-14-f0-frontend-foundation-design.md` (two corrections)
- Modify: `docs/superpowers/HANDOFF.md`, `docs/ROADMAP.md`

- [ ] **Step 1: Append challenge #80**

At the end of `engineering-challenges.md`:
```markdown
## Challenge 80 — A refresh rotation that was already race-safe, and a lost reply that was not

**Phase:** Design + Implementation

### The problem

Moving the refresh token into an httpOnly cookie shared by every tab raised two failure modes. The
first design draft named the wrong one. It claimed `RefreshTokenService.rotate` could fork a session
under concurrency ("no lock, no `@Version`"), and planned a test proving exactly one successor
survives. `RefreshToken extends BaseEntity`, which carries `@Version`: the losing rotation already
failed its versioned UPDATE and rolled back its successor. The test as planned would have passed on
the unfixed code and proved nothing. The real defect was the loser's *status* — the optimistic-lock
exception maps to **409**, where a refresh failure must be **401**.

The failure mode the draft dismissed was the real one. It declined token-family revocation "because
it would log every tab out when a refresh response is lost on 4G" — but a plain 401 does the same.
If the server commits a rotation and the response (with its `Set-Cookie`) is lost, the browser keeps
the revoked cookie, every tab shares it, and the next refresh signs the user out everywhere.

### Why it's hard

It is the lost-ACK problem on a non-idempotent write: the client cannot tell "not applied" from
"applied, reply lost". A browser lock (Web Locks) serializes tabs but does nothing about a lost reply.
Any recovery re-opens a replay window for a stolen token, so the recovery condition has to separate a
lost reply from a replay.

### The solution

Rotation is a conditional native `UPDATE … WHERE revoked_at IS NULL` after inserting the successor; a
zero-row match throws 401 and rolls the insert back. A presented token that was revoked by rotation
within 30 s may be re-presented **once**, and only while the successor that rotation minted is **still
unused** — if the legitimate client already used it, re-presentation is a replay and gets 401. The
orphaned successor is revoked. `grace_used_at` (V35) makes it single-use; `SELECT … FOR UPDATE`
serializes concurrent grace attempts. The race test forces the overlap by holding the row lock from a
second JDBC connection and polling `pg_stat_activity` until `rotate()` is blocked, and it was seen
failing with the 409 before the fix.

### Lesson

Before designing a fix for a race, make the race happen and read the actual outcome — the entity's
superclass had already solved the problem the spec was about to solve again. And test a "we declined
X because of Y" rationale by asking whether the chosen alternative also suffers Y: here it did, and
that turned a dismissed failure mode into the design's central requirement.
```

- [ ] **Step 2: Annotations reference**

Run `grep -n "@Query\` |" docs/superpowers/annotations-reference.md` and add, next to the `@Query` row, a row in the same table format:
```markdown
| `@Modifying` | `org.springframework.data.jpa.repository` | Marks an `@Query` as a write (UPDATE/DELETE) so Spring Data executes it with `executeUpdate` and returns the affected-row count. `flushAutomatically = true` flushes pending entity changes first — `RefreshTokenRepository.revokeIfLive` depends on it so the successor row it points at is already inserted. A native modifying query bypasses `@Version` and auditing, so those columns are maintained by hand in the SQL. | — |
```
Check `@ConfigurationProperties` and `@DefaultValue` rows exist (they do); no new row needed for them.

- [ ] **Step 3: Correct the spec**

In the spec:
- §3.7 "Bean validation (400)" bullet: replace "(`NotBlank`, `Size`, `Pattern`, `Email`)" with "converted to SCREAMING_SNAKE (`NOT_BLANK`, `SIZE`, `PATTERN`, `EMAIL`)".
- §3.3, the bullet "An `OptimisticLockingFailureException` inside `rotate` maps to 401, never 409." → replace with "`rotate` performs no versioned entity write (the presented row is changed only by conditional native UPDATEs), so no optimistic-lock exception — and no 409 — can arise from it."
- §3.7 GSTIN code list: ensure it reads `GSTIN_REQUIRED` / `GSTIN_LENGTH` / `GSTIN_CHARSET` / `GSTIN_CHECKSUM`.

- [ ] **Step 4: Full verification from clean**

```bash
cd backend && ./gradlew clean check 2>&1 | tail -15
find . -path '*build/test-results/test/*.xml' -print0 | xargs -0 grep -ho 'tests="[0-9]*"' | awk -F'"' '{s+=$2} END {print s}'
find . -path '*build/test-results/test/*.xml' -print0 | xargs -0 grep -ho 'failures="[0-9]*"\|errors="[0-9]*"' | sort | uniq -c
cd ..
```
Expected: `BUILD SUCCESSFUL`; failures and errors all `="0"`. New tests added by this plan: Task 2 (3), Task 3 (3 primitives + 3 wire + 3 controller = 9), Task 4 (1), Task 5 (1), Task 6 (5), Task 7 (6), Task 8 (3), Task 9 (2), Task 10 (1), Task 11 (3 + 1 = 4), Task 12 (2) = **37**, so the expected total is **663** (626 + 37) unless Task 1 changed the baseline. If the number differs, reconcile by listing test classes changed in `git diff --stat main -- backend/src/test backend/platform` before claiming done.

- [ ] **Step 5: Handoff and roadmap**

In `docs/superpowers/HANDOFF.md`, add at the top a dated 2026-09-14 (or actual date) section stating: F0a is on branch `f0a-backend-auth` at `<tip sha>`, the verified test count from Step 4, which Dependabot branches were merged or skipped (Task 1), that the contract now has zero `*/*` responses and a cookie-based refresh, and that **the next step is the F0b plan**, written against the regenerated `docs/api/openapi.yaml`. In `docs/ROADMAP.md` Part 6 item 4's "Why now" cell, append "F0a (backend auth prep) built on branch `f0a-backend-auth`; F0b next."

- [ ] **Step 6: Commit the docs**

```bash
git add docs/superpowers/engineering-challenges.md docs/superpowers/annotations-reference.md \
  docs/superpowers/specs/2026-09-14-f0-frontend-foundation-design.md docs/superpowers/HANDOFF.md docs/ROADMAP.md
git commit -m "docs: record F0a -- challenge 80, @Modifying, spec corrections, handoff"
```

- [ ] **Step 7: Finish the branch**

Use `superpowers:finishing-a-development-branch`. Before merging, dispatch the general code review. Specialist reviewers: none of the five frontend lenses' "Use when" clauses match a backend-only diff except `frontend-review-security` ("touches browser auth (tokens, refresh, cookies, CSRF, logout)… the backend endpoints the frontend depends on") — dispatch that one on the branch range. Pushing is an outward-facing act: ask the owner before `git push`.
