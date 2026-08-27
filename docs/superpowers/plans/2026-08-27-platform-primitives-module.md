# `platform-primitives` Module Extraction — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract every zero-dependency primitive — the five exception types, the money wire
format, and the GST value types — into a real Gradle module `platform-primitives`, so that a
dependency on them becomes a declared edge instead of a package convention.

**Architecture:** `backend/` becomes a two-project Gradle build. The root project keeps `src/` and
the Spring Boot plugin exactly as they are; a new subproject `platform/platform-primitives` holds
`com.easycrm.platform.{error,money,gst}` and is consumed by the root via
`implementation(project(":platform:platform-primitives"))`. **Package names do not change**, so not
one service `import` moves. The module has no runtime Spring dependency: `spring-context` and
`spring-boot-autoconfigure` are `compileOnly`, and the Jackson auto-configuration simply does not
activate where Boot is absent. On top of the move, three things are added that the module boundary
is what makes possible: `EventJson` (a second, explicitly-built mapper for the event wire),
`MoneyAutoConfiguration` (so the Jackson module survives a service that stops component-scanning
`com.easycrm.platform`), and two ArchUnit rules that turn TB3 from a review catch into a build
failure.

**Tech Stack:** Gradle 9.6.1 (multi-project, Kotlin DSL) · Java 25 toolchain · Spring Boot 4.1.0 ·
Jackson 3 (`tools.jackson.*`, databind 3.1.4) · JUnit 5 · AssertJ · ArchUnit 1.4.1 · Testcontainers
1.21.3 (root project only).

**Spec:** [`../../architecture/2026-08-26-platform-primitives-lld.md`](../../architecture/2026-08-26-platform-primitives-lld.md)
(LLD #1 of 6). Parent: [`../specs/2026-08-26-shared-platform-modules-design.md`](../specs/2026-08-26-shared-platform-modules-design.md).
Thread handoff: [`../../architecture/2026-08-27-platform-llds-handoff.md`](../../architecture/2026-08-27-platform-llds-handoff.md).

---

## Global Constraints

Copied verbatim from `CLAUDE.md`, the LLD, and the project handoff. Every task's requirements
implicitly include this section.

- **Baseline:** `231 tests, 0 failures` at commit `ac4eaca`. Confirm before Task 1 and after every
  task. If the number differs unexpectedly, stop and reconcile before continuing.
- **Package names do not change.** `com.easycrm.platform.error`, `.money` and `.gst` keep their
  fully-qualified names after the move. No service changes a single `import`. If a task requires an
  import change outside `platform-primitives`, the task is wrong.
- **Money is never a `double`.** `BigDecimal` in Java, `NUMERIC` in Postgres, JSON **string** on the
  wire. `toPlainString()`, never `toString()`. The serialiser **does not round** — rounding is
  `GstCalculator`'s job (per line, `HALF_UP`, then sum) and the column's job.
- **Java toolchain is 25.** The shell default is JDK 21; do **not** change it. Always use the
  wrapper: `cd backend && ./gradlew …`.
- **ArchUnit is pinned to 1.4.1.** 1.3.0 silently imports zero classes on Java 25 bytecode and
  passes every rule vacuously. Any new rule must be proven to fail before it is trusted to pass.
- **Docker must be running** for the root project's integration tests: `open -a Docker`, wait for
  `docker info` to succeed. Leave the user's `langfuse-postgres-1` container on `localhost:5432`
  alone; Testcontainers uses its own random port.
- **Commits:** author as `divyam <divyam.0444@gmail.com>` (repo git config is already correct — use
  plain `git commit`, no `-c user.name=…` override). **Never** add a `Co-Authored-By: Claude`
  trailer or mention Claude/AI anywhere in a commit message.
- **TDD, one task per commit:** failing test → run to confirm it fails → minimal code → run to
  confirm it passes → commit.
- **Log engineering challenges** to `docs/superpowers/engineering-challenges.md` in the same change
  that solves them (Problem → why it's hard → Solution → Lesson). Task 8 does the final sweep, but
  do not save up an entry that belongs to the task that earned it.
- **Keep `docs/superpowers/annotations-reference.md` current** — a row for every new annotation
  (`@AutoConfiguration` lands in Task 2).
- `spring.jpa.open-in-view: false` is load-bearing. Nothing in this plan touches it.

### Baseline command

The build becomes multi-project in Task 1, so the count must be summed across **both** projects'
result directories from Task 1 onward. Use this exact command throughout:

```bash
cd backend && ./gradlew clean test 2>&1 | tail -5
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'failures="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "failures:", s}'
```

An unqualified `./gradlew test` from `backend/` runs the `test` task in the root project **and**
every subproject, so one command still covers everything.

### Before starting

```bash
cd /Users/divyam/Documents/easy-crm
git checkout -b platform-primitives-module
```

Work on that branch off `main`. `main` is at `ac4eaca` with a clean tree.

---

## File Structure

**New subproject** — `backend/platform/platform-primitives/`:

| File | Responsibility |
|---|---|
| `build.gradle.kts` | `java-library`, Java 25 toolchain, Boot BOM for versions, `api` on jackson-databind, `compileOnly` on spring-context + spring-boot-autoconfigure, test deps |
| `src/main/java/com/easycrm/platform/error/{NotFound,Unauthorized,Forbidden,Conflict,Validation}Exception.java` | The error vocabulary. Zero imports beyond `java.util.Map`. **Moved verbatim** from the root project |
| `src/main/java/com/easycrm/platform/money/BigDecimalStringModule.java` | Serialises every `BigDecimal` as a plain-notation JSON string. **Moved verbatim** |
| `src/main/java/com/easycrm/platform/money/MoneyAutoConfiguration.java` | Registers the module as a `JacksonModule` bean. **Replaces** `MoneyJacksonConfig` (Task 2) |
| `src/main/java/com/easycrm/platform/money/EventJson.java` | **NEW** (Task 4). The mapper for anything persisted or published. Owns the event wire's configuration outright |
| `src/main/java/com/easycrm/platform/gst/Gstin.java` | Validated 15-char GSTIN. **Moved**, then gains the state-prefix check (Task 6) |
| `src/main/java/com/easycrm/platform/gst/StateCode.java` | Valid GST state codes. **Moved verbatim** |
| `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | **NEW** (Task 2). One line naming `MoneyAutoConfiguration` |
| `src/test/java/com/easycrm/platform/money/BigDecimalStringModuleTest.java` | **NEW** (Task 3). Closes MF5 |
| `src/test/java/com/easycrm/platform/money/EventJsonTest.java` | **NEW** (Task 4). Carries the TB3 regression test |
| `src/test/java/com/easycrm/platform/gst/GstinTest.java` | **Moved** from the root project, then extended (Task 6) |
| `src/test/java/com/easycrm/platform/PrimitivesModuleArchTest.java` | **NEW** (Task 5). Rule R2 — the module's own classpath is the assertion |

**Modified in the root project** — `backend/`:

| File | Change |
|---|---|
| `settings.gradle.kts` | `include(":platform:platform-primitives")` |
| `build.gradle.kts` | `implementation(project(":platform:platform-primitives"))` |
| `src/main/java/com/easycrm/platform/money/MoneyJacksonConfig.java` | **Deleted** in Task 2 (superseded by `MoneyAutoConfiguration`) |
| `src/main/java/com/easycrm/iam/web/dto/SignupRequest.java` | Task 7 — `stateCode` pattern comment; validation moves to the service |
| `src/main/java/com/easycrm/iam/AuthService.java` | Task 7 — seller GSTIN/state validated at signup (MF1) |
| `src/test/java/com/easycrm/arch/PlatformPrimitivesArchTest.java` | **NEW** (Task 5). Rule R1 — needs the whole application on the classpath, so it cannot live in the subproject |
| `src/test/java/com/easycrm/platform/money/MoneyWireFormatTest.java` | Task 2 — comment recording the `ProductController` dependency (MF4). Assertions unchanged |
| `src/test/java/com/easycrm/iam/AuthServiceSignupTest.java` | Task 7 — new cases for an invalid seller state code and GSTIN |

**Deliberately unmoved.** `ApiExceptionHandler` and `PageResponse` stay in the root project — they
belong to `platform-web`, which this plan does not create. This leaves
`com.easycrm.platform.error` as a **split package**: five types in the subproject jar,
`ApiExceptionHandler` in the root. That is legal on the classpath (there is no `module-info.java`
anywhere in this build, so JPMS's split-package prohibition does not apply) and it is why Task 5's
R2 rule is scoped by *module classpath* rather than by package name — a package-scoped rule would
try to judge `ApiExceptionHandler` and fail.

---

## Task 1: The module exists and nothing changes

Pure structural move. The deliverable is a two-project build in which all 231 tests still pass and
no `import` outside the new subproject has changed. Nothing is added here — adding and moving in the
same commit would make a bisect useless.

**Files:**
- Create: `backend/platform/platform-primitives/build.gradle.kts`
- Modify: `backend/settings.gradle.kts`
- Modify: `backend/build.gradle.kts`
- Move (`git mv`, contents byte-identical):
  - `backend/src/main/java/com/easycrm/platform/error/{NotFoundException,UnauthorizedException,ForbiddenException,ConflictException,ValidationException}.java`
    → `backend/platform/platform-primitives/src/main/java/com/easycrm/platform/error/`
  - `backend/src/main/java/com/easycrm/platform/money/{BigDecimalStringModule,MoneyJacksonConfig}.java`
    → `backend/platform/platform-primitives/src/main/java/com/easycrm/platform/money/`
  - `backend/src/main/java/com/easycrm/platform/gst/{Gstin,StateCode}.java`
    → `backend/platform/platform-primitives/src/main/java/com/easycrm/platform/gst/`
  - `backend/src/test/java/com/easycrm/platform/gst/GstinTest.java`
    → `backend/platform/platform-primitives/src/test/java/com/easycrm/platform/gst/`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: the Gradle project path `:platform:platform-primitives`, and the guarantee that
  `com.easycrm.platform.error.*`, `com.easycrm.platform.money.BigDecimalStringModule`,
  `com.easycrm.platform.gst.Gstin` and `com.easycrm.platform.gst.StateCode` resolve from that
  project's jar under their unchanged FQNs. Every later task depends on this.

- [ ] **Step 1: Confirm the baseline before touching anything**

```bash
open -a Docker            # skip if `docker info` already succeeds
cd /Users/divyam/Documents/easy-crm/backend && ./gradlew clean test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`. Then count:

```bash
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
```

Expected: `tests: 231`. **If it is not 231, stop and reconcile.** Everything below assumes it.

- [ ] **Step 2: Declare the subproject**

`backend/settings.gradle.kts` — full new contents:

```kotlin
rootProject.name = "easycrm-backend"

include(":platform:platform-primitives")
```

- [ ] **Step 3: Write the subproject build file**

Create `backend/platform/platform-primitives/build.gradle.kts`:

```kotlin
plugins {
    `java-library`
}

group = "com.easycrm"
version = "0.0.1-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

repositories { mavenCentral() }

dependencies {
    // The Boot BOM is the single source of dependency versions across both projects.
    // The Spring Boot Gradle plugin is deliberately NOT applied here: this is a plain
    // library jar, not an application, and applying it would attach a bootJar task.
    api(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))

    // `api`, not `implementation`: EventJson.mapper() returns a JsonMapper (Task 4) and
    // platform-outbox must see the type. Every consumer already receives jackson-databind
    // via spring-boot-starter-web, so this widens nobody's classpath in practice.
    api("tools.jackson.core:jackson-databind")

    // compileOnly, and this is the point of the module: the value types, the exception
    // vocabulary and EventJson must all work with no Spring on the runtime classpath, so
    // notification-svc can take this jar without inheriting a servlet stack. compileOnly
    // is non-transitive in Gradle, so spring-context must be named even though
    // spring-boot-autoconfigure depends on it.
    compileOnly("org.springframework:spring-context")
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    // 1.4.x parses Java 25 bytecode; 1.3.0 silently skips it and passes vacuously.
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> { useJUnitPlatform() }
```

If the `junit-bom` coordinate above conflicts with the version the Boot BOM already manages, drop
the `platform("org.junit:junit-bom:…")` line entirely — `spring-boot-dependencies` manages JUnit —
and re-run. Do not pin two BOMs against each other.

- [ ] **Step 4: Move the files with `git mv` so history follows them**

```bash
cd /Users/divyam/Documents/easy-crm/backend
P=platform/platform-primitives/src/main/java/com/easycrm/platform
mkdir -p "$P/error" "$P/money" "$P/gst"
mkdir -p platform/platform-primitives/src/test/java/com/easycrm/platform/gst

for f in NotFoundException UnauthorizedException ForbiddenException ConflictException ValidationException; do
  git mv "src/main/java/com/easycrm/platform/error/$f.java" "$P/error/$f.java"
done
git mv src/main/java/com/easycrm/platform/money/BigDecimalStringModule.java "$P/money/"
git mv src/main/java/com/easycrm/platform/money/MoneyJacksonConfig.java      "$P/money/"
git mv src/main/java/com/easycrm/platform/gst/Gstin.java                     "$P/gst/"
git mv src/main/java/com/easycrm/platform/gst/StateCode.java                 "$P/gst/"
git mv src/test/java/com/easycrm/platform/gst/GstinTest.java \
       platform/platform-primitives/src/test/java/com/easycrm/platform/gst/GstinTest.java
```

Do **not** edit any of the moved files in this task. `ApiExceptionHandler.java` stays where it is.

- [ ] **Step 5: Run the build and watch it fail**

```bash
cd /Users/divyam/Documents/easy-crm/backend && ./gradlew compileJava 2>&1 | tail -20
```

Expected: FAIL — the root project's `compileJava` reports `package com.easycrm.platform.error does
not exist` (and `.gst`, `.money`) from `CustomerService`, `ApiExceptionHandler` and others. This is
the red step: it proves the root project genuinely lost the classes and will only get them back
through a declared dependency edge.

- [ ] **Step 6: Declare the dependency in the root project**

In `backend/build.gradle.kts`, add as the **first** line of the `dependencies { … }` block:

```kotlin
    // The bottom of the platform DAG: exception vocabulary, money wire format, GST value
    // types. A declared edge rather than a package convention — see LLD #1 and TB3.
    implementation(project(":platform:platform-primitives"))
```

- [ ] **Step 7: Run the full suite and confirm nothing changed**

```bash
cd /Users/divyam/Documents/easy-crm/backend && ./gradlew clean test 2>&1 | tail -5
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'failures="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "failures:", s}'
```

Expected: `BUILD SUCCESSFUL`, `tests: 231`, `failures: 0` — the same 231, now spread across two
projects (`GstinTest`'s 5 having moved to the subproject). A different total means a test source set
is not being run; find out which before continuing.

- [ ] **Step 8: Prove the app still boots and serves**

The unit suite would pass even if auto-configuration were subtly broken, so run one integration test
that exercises the real HTTP stack end to end:

```bash
cd /Users/divyam/Documents/easy-crm/backend && ./gradlew test --tests '*MoneyWireFormatTest' 2>&1 | tail -5
```

Expected: PASS. `MoneyJacksonConfig` is still a component-scanned `@Configuration` and
`@SpringBootApplication` sits at `com.easycrm`, so the scan reaches into the new jar. Task 2 removes
that reliance.

- [ ] **Step 9: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add -A
git commit -m "build: extract platform-primitives into its own Gradle module

The error vocabulary, the money wire format and the GST value types move
into platform/platform-primitives and the root project reaches them through
implementation(project(...)) instead of a shared source tree.

Package names are unchanged, so no service edits an import; the whole change
is a settings file, a build file and eight git mv's. Done now rather than
after the service split, when the same move would touch five build files.

The module carries no runtime Spring dependency — spring-context and
spring-boot-autoconfigure are compileOnly — so a service with no servlet on
its classpath can take it without inheriting one.

231 tests, unchanged."
```

---

## Task 2: The Jackson module survives losing the component scan

`MoneyJacksonConfig` is a `@Configuration` found by component scan from `com.easycrm`. Today that
still reaches the subproject jar, because `EasyCrmApplication` sits at `com.easycrm`. The moment a
service scans from `com.easycrm.sales`, the bean disappears and money crosses the HTTP wire as a
JSON number with no error anywhere — MB1. Auto-configuration is how the bean stops depending on
where anyone's application class happens to live.

There is a trap here worth naming, because it is the reason this task has the test it does: an
`@AutoConfiguration` class is meta-annotated `@Configuration`, and this one **will still sit inside
the `com.easycrm` scan range**. So it is reachable by two mechanisms at once — the component scan
and the `.imports` file. The test below exists to prove that produces exactly one module bean and a
context that starts, not a `BeanDefinitionOverrideException`.

**Files:**
- Create: `backend/platform/platform-primitives/src/main/java/com/easycrm/platform/money/MoneyAutoConfiguration.java`
- Create: `backend/platform/platform-primitives/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Delete: `backend/platform/platform-primitives/src/main/java/com/easycrm/platform/money/MoneyJacksonConfig.java`
- Create: `backend/src/test/java/com/easycrm/platform/money/MoneyModuleWiringTest.java`
- Modify: `backend/src/test/java/com/easycrm/platform/money/MoneyWireFormatTest.java` (comment only)
- Modify: `docs/superpowers/annotations-reference.md`

**Interfaces:**
- Consumes: `:platform:platform-primitives` and `BigDecimalStringModule` from Task 1.
- Produces: `com.easycrm.platform.money.MoneyAutoConfiguration`, registered via
  `AutoConfiguration.imports`, contributing exactly one `tools.jackson.databind.JacksonModule` bean
  named `bigDecimalStringModule`. Task 5's R1 rule exempts `com.easycrm.platform.money..`, which
  this class is inside.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/easycrm/platform/money/MoneyModuleWiringTest.java`:

```java
package com.easycrm.platform.money;

import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.JacksonModule;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MoneyAutoConfiguration is registered through AutoConfiguration.imports AND sits inside the
 * com.easycrm package that EasyCrmApplication component-scans, so it is reachable twice. Spring
 * de-duplicates configuration classes by class name, but "should" is not "does" — this test is
 * the proof, and it fails loudly (context refresh) rather than silently if that ever changes.
 */
class MoneyModuleWiringTest extends IntegrationTest {

    @Autowired ApplicationContext ctx;

    @Test
    void exactlyOneBigDecimalStringModuleBeanIsRegistered() {
        Map<String, JacksonModule> modules = ctx.getBeansOfType(JacksonModule.class);

        assertThat(modules.values())
            .filteredOn(BigDecimalStringModule.class::isInstance)
            .as("registered twice = a duplicate-definition trap; zero = MB1, money on the wire "
              + "as a JSON number with no error anywhere")
            .hasSize(1);
    }

    @Test
    void theAutoConfigurationIsWhatRegisteredIt() {
        assertThat(ctx.getBeanDefinitionNames())
            .as("the bean must arrive through auto-configuration, not component scan, or it "
              + "disappears the day a service scans from com.easycrm.sales instead")
            .contains("com.easycrm.platform.money.MoneyAutoConfiguration");
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
cd /Users/divyam/Documents/easy-crm/backend && ./gradlew test --tests '*MoneyModuleWiringTest' 2>&1 | tail -30
```

Expected: `exactlyOneBigDecimalStringModuleBeanIsRegistered` PASSES (the component-scanned
`MoneyJacksonConfig` already provides exactly one) and `theAutoConfigurationIsWhatRegisteredIt`
**FAILS** — no bean definition by that name exists. That single failure is precisely the gap this
task closes.

- [ ] **Step 3: Write the auto-configuration**

Create `backend/platform/platform-primitives/src/main/java/com/easycrm/platform/money/MoneyAutoConfiguration.java`:

```java
package com.easycrm.platform.money;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.JacksonModule;

/**
 * Registers {@link BigDecimalStringModule} on the application ObjectMapper.
 *
 * <p>Auto-configuration rather than a component-scanned {@code @Configuration}: this module is a
 * jar, and a service whose {@code @SpringBootApplication} sits at {@code com.easycrm.sales} never
 * scans {@code com.easycrm.platform}. The bean would simply not exist, and the only symptom would
 * be money crossing the HTTP wire as a JSON number — no exception, no log line (MB1).
 *
 * <p>{@code @ConditionalOnClass(JacksonModule.class)} keeps the module usable with no Jackson and
 * no Spring at all, which is what lets notification-svc take this jar without a servlet stack.
 */
@AutoConfiguration
@ConditionalOnClass(JacksonModule.class)
public class MoneyAutoConfiguration {

    // Boot 4's JacksonAutoConfiguration injects a Collection<JacksonModule> into its mapper-builder
    // customizer, so any JacksonModule bean is registered regardless of which configuration class
    // declared it. SimpleModule implements JacksonModule.
    @Bean
    JacksonModule bigDecimalStringModule() {
        return new BigDecimalStringModule();
    }
}
```

- [ ] **Step 4: Register it**

Create
`backend/platform/platform-primitives/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
with exactly one line and a trailing newline:

```
com.easycrm.platform.money.MoneyAutoConfiguration
```

- [ ] **Step 5: Delete the superseded configuration**

```bash
cd /Users/divyam/Documents/easy-crm/backend
git rm platform/platform-primitives/src/main/java/com/easycrm/platform/money/MoneyJacksonConfig.java
```

- [ ] **Step 6: Run the test to confirm it passes**

```bash
cd /Users/divyam/Documents/easy-crm/backend && ./gradlew test --tests '*MoneyModuleWiringTest' 2>&1 | tail -20
```

Expected: both tests PASS.

If instead the context fails to refresh with `BeanDefinitionOverrideException` for
`bigDecimalStringModule`, the double-reachability trap is real rather than theoretical. **Do not fix
it by enabling bean-definition overriding.** Fix it by excluding the class from the component scan
in `EasyCrmApplication`:

```java
@SpringBootApplication(excludeFilters = @ComponentScan.Filter(
    type = FilterType.ASSIGNABLE_TYPE, classes = MoneyAutoConfiguration.class))
```

and log the whole thing as an engineering challenge in this same commit — an auto-configuration
class that is also inside the application's scan range is exactly the kind of non-obvious trap the
log exists for.

- [ ] **Step 7: Record MF4 in the tripwire test**

`MoneyWireFormatTest` is the only proof the Jackson module is actually wired onto the application
mapper, and it asserts through `ProductController` — so refactoring that controller would silently
remove the tripwire. Add this comment directly above the `@Test` method in
`backend/src/test/java/com/easycrm/platform/money/MoneyWireFormatTest.java`, changing nothing else:

```java
    // MF4: this is the only end-to-end proof that BigDecimalStringModule reached the application
    // ObjectMapper — MoneyModuleWiringTest proves the bean exists, this proves it took effect on
    // the real HTTP wire. It asserts through ProductController only because a product is the
    // cheapest thing to create; if that controller is ever removed, move this assertion rather
    // than deleting it, or MB1 loses its tripwire.
```

- [ ] **Step 8: Run the full suite**

```bash
cd /Users/divyam/Documents/easy-crm/backend && ./gradlew clean test 2>&1 | tail -5
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
```

Expected: `BUILD SUCCESSFUL`, `tests: 233` (231 + 2 new).

- [ ] **Step 9: Add the annotation reference row**

In `docs/superpowers/annotations-reference.md`, add a row for `@AutoConfiguration` in whatever table
holds the Spring Boot annotations, matching the file's existing column layout. The content:

- **Origin:** `org.springframework.boot.autoconfigure` (artifact `spring-boot-autoconfigure`).
- **Purpose:** marks a class as an auto-configuration, applied only when it is named in
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Unlike
  `@Configuration`, it is applied *after* user beans, so `@ConditionalOnMissingBean` can back off.
- **Composition:** meta-annotated `@Configuration(proxyBeanMethods = false)` plus
  `@AutoConfigureBefore`/`@AutoConfigureAfter` aliases — which is why a class carrying it is still
  a candidate for component scan if it happens to sit under a scanned package.

If a stub row already exists marked "(added in Task N)", replace the stub rather than adding a
second row.

- [ ] **Step 10: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add -A
git commit -m "feat: register the money Jackson module by auto-configuration

MoneyJacksonConfig was a component-scanned @Configuration. That works only
because EasyCrmApplication sits at com.easycrm and the scan happens to reach
into the new jar; a service scanning from com.easycrm.sales would get no bean
and no error, and every BigDecimal would cross the HTTP wire as a JSON number
(MB1).

MoneyAutoConfiguration is named in AutoConfiguration.imports, so the bean no
longer depends on where anyone's application class lives.

MoneyModuleWiringTest asserts both halves: exactly one BigDecimalStringModule
bean, and that it arrived by auto-configuration rather than by scan. The first
assertion matters because the class is reachable both ways here — it is still
inside the scanned package — and a duplicate definition would be a context
refresh failure, not a warning.

Also records MF4 in MoneyWireFormatTest: it is the only end-to-end proof the
module reached the application mapper, and it asserts through ProductController.

233 tests."
```

---

## Task 3: Unit-test the serialiser (MF5)

There is no unit test of `BigDecimalStringModule` today — only the heavyweight
`MoneyWireFormatTest`, so a change to the serialiser fails one Spring integration test with an
opaque message. These tests need no Spring at all and live in the subproject.

**Files:**
- Create: `backend/platform/platform-primitives/src/test/java/com/easycrm/platform/money/BigDecimalStringModuleTest.java`

**Interfaces:**
- Consumes: `com.easycrm.platform.money.BigDecimalStringModule` (Task 1).
- Produces: nothing other tasks depend on.

- [ ] **Step 1: Write the failing test**

Create `backend/platform/platform-primitives/src/test/java/com/easycrm/platform/money/BigDecimalStringModuleTest.java`:

```java
package com.easycrm.platform.money;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MF5: the serialiser had no unit test at all — only MoneyWireFormatTest, a full @SpringBootTest.
 * These run in milliseconds and say exactly which property broke.
 *
 * <p>It is a numeric-precision serialiser, not a money serialiser. QuotationItem carries nine
 * BigDecimal fields and only six are money: qty is NUMERIC(18,3) and gstRate/discountPct are
 * NUMERIC(18,4). A quantity of 2.5 KG has exactly the IEEE-754 problem money has, so keying off
 * the Java type rather than the field's meaning is deliberate.
 */
class BigDecimalStringModuleTest {

    private final JsonMapper mapper =
        JsonMapper.builder().addModule(new BigDecimalStringModule()).build();

    private String write(BigDecimal v) {
        return mapper.writeValueAsString(new Holder(v));
    }

    record Holder(BigDecimal amount) {}

    @Test
    void serialisesAsAQuotedStringNotANumber() {
        assertThat(write(new BigDecimal("12.50"))).isEqualTo("{\"amount\":\"12.50\"}");
    }

    @Test
    void preservesScaleRatherThanNormalising() {
        // 12.50 must not become 12.5: the column is NUMERIC(18,2) and the wire shows what is stored.
        assertThat(write(new BigDecimal("12.50"))).contains("\"12.50\"");
        assertThat(write(new BigDecimal("18.0000"))).contains("\"18.0000\"");
    }

    @Test
    void doesNotRound() {
        // Rounding is GstCalculator's job (per line, HALF_UP, then sum) and the column's job.
        // A wire that silently rounded would hide the disagreement with Tally this design exists
        // to prevent.
        assertThat(write(new BigDecimal("1250"))).contains("\"1250\"");
        assertThat(write(new BigDecimal("0.123456"))).contains("\"0.123456\"");
    }

    @Test
    void usesPlainNotationNeverScientific() {
        // toString() would emit "1.25E+3", which no Indian accountant and no Tally import accepts.
        assertThat(write(new BigDecimal("1.25E+3"))).contains("\"1250\"");
        assertThat(write(new BigDecimal("0.00000001"))).contains("\"0.00000001\"");
    }

    @Test
    void handlesNegativeAndZero() {
        assertThat(write(new BigDecimal("-1.00"))).contains("\"-1.00\"");
        assertThat(write(new BigDecimal("0.00"))).contains("\"0.00\"");
    }

    @Test
    void deserialisationStillAcceptsBothStringAndNumber() {
        // Not touched by the module: Jackson already coerces. A client can still send a JSON
        // number, which is contained rather than prevented — the server recomputes every total
        // and is authoritative, and the client preview is never trusted.
        assertThat(mapper.readValue("{\"amount\":\"12.50\"}", Holder.class).amount())
            .isEqualByComparingTo("12.50");
        assertThat(mapper.readValue("{\"amount\":12.50}", Holder.class).amount())
            .isEqualByComparingTo("12.50");
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
cd /Users/divyam/Documents/easy-crm/backend && ./gradlew :platform:platform-primitives:test --tests '*BigDecimalStringModuleTest' 2>&1 | tail -30
```

Expected: the test class **compiles and runs**, and every test **passes on the first run** — the
serialiser already exists and already behaves this way. That is the correct outcome here: these are
characterisation tests for existing behaviour, not a new feature, so there is no red step to
manufacture. What matters is that the run is real. Confirm it actually executed six tests rather
than zero:

```bash
grep -o 'tests="[0-9]*"' platform/platform-primitives/build/test-results/test/TEST-com.easycrm.platform.money.BigDecimalStringModuleTest.xml
```

Expected: `tests="6"`. **If any test does fail, stop** — the serialiser does not do what the LLD
says it does, and that is a finding, not a test to adjust.

- [ ] **Step 3: Prove the tests can fail**

A characterisation test nobody has seen fail is a test nobody should trust. Temporarily change
`BigDecimalStringModule.BigDecimalStringSerializer.serialize` to:

```java
            gen.writeNumber(value);
```

Run the test again. Expected: `serialisesAsAQuotedStringNotANumber`, `preservesScaleRatherThanNormalising`,
`doesNotRound`, `usesPlainNotationNeverScientific` and `handlesNegativeAndZero` all FAIL. **Then
revert the change** (`git checkout -- platform/platform-primitives/src/main/java/com/easycrm/platform/money/BigDecimalStringModule.java`)
and re-run to confirm all six pass again.

- [ ] **Step 4: Run the full suite**

```bash
cd /Users/divyam/Documents/easy-crm/backend && ./gradlew clean test 2>&1 | tail -5
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
```

Expected: `tests: 239` (233 + 6).

- [ ] **Step 5: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add -A
git commit -m "test: unit-test BigDecimalStringModule (MF5)

The serialiser had no unit test — only MoneyWireFormatTest, a full
@SpringBootTest, so any change to it failed one heavyweight test with an
opaque message.

Six characterisation tests pin the properties a reader would otherwise
assume wrong: scale is preserved and not normalised, nothing is rounded,
notation is plain and never scientific, and deserialisation is untouched.
Each was confirmed to fail against a writeNumber() serialiser before being
trusted to pass.

239 tests."
```

---

## Task 4: `EventJson` — the second wire

This is the module's one real design decision, and the reason it is a module at all.

Money now crosses two wires. HTTP responses use the application `ObjectMapper`, owned by Spring Boot
and versioned by the API. Outbox `payload` JSONB → SNS → SQS is a different contract: additive-only
and readable for years. TB3 is the bug where an `ObjectMapper` constructed inside the outbox writer
does not carry `BigDecimalStringModule`, so money reaches SNS as an IEEE-754 double after the entire
stack avoided exactly that.

Injecting the application mapper is the obvious fix and is rejected: someone sets
`spring.jackson.default-property-inclusion=non_null` to slim an API response, and every subsequent
outbox payload silently drops its null fields — after which a consumer can no longer distinguish
"field absent because the producer is older" from "field present and null". Two wires with different
owners and different change cadences get different mappers.

**Files:**
- Create: `backend/platform/platform-primitives/src/main/java/com/easycrm/platform/money/EventJson.java`
- Create: `backend/platform/platform-primitives/src/test/java/com/easycrm/platform/money/EventJsonTest.java`
- Create: `backend/src/test/java/com/easycrm/platform/money/EventJsonDivergenceTest.java`

**Interfaces:**
- Consumes: `BigDecimalStringModule` (Task 1).
- Produces: `public static tools.jackson.databind.json.JsonMapper EventJson.mapper()` — a single
  immutable instance, safe to call from any thread. `platform-outbox` will consume this; it is the
  reason `jackson-databind` is `api` and not `implementation` in the subproject build file.

- [ ] **Step 1: Write the failing test**

Create `backend/platform/platform-primitives/src/test/java/com/easycrm/platform/money/EventJsonTest.java`:

```java
package com.easycrm.platform.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EventJsonTest {

    record Payload(BigDecimal grandTotal, Instant occurredAt, String note) {}

    @Test
    void serialisesBigDecimalAsAString() {
        // THE TB3 REGRESSION TEST. If this fails, money reaches SNS as an IEEE-754 double and
        // every consumer downstream inherits the rounding error BigDecimal exists to prevent.
        String json = EventJson.mapper()
            .writeValueAsString(new Payload(new BigDecimal("1180.00"), Instant.EPOCH, null));

        assertThat(json).contains("\"grandTotal\":\"1180.00\"");
        assertThat(json).doesNotContain("\"grandTotal\":1180");
    }

    @Test
    void writesTimestampsAsIso8601NotEpochNumbers() {
        // An additive-only contract read for years must not depend on a Jackson default. A numeric
        // epoch is also ambiguous about its unit in a way an ISO-8601 string never is.
        String json = EventJson.mapper()
            .writeValueAsString(new Payload(BigDecimal.ONE, Instant.parse("2026-08-27T09:15:30Z"), null));

        assertThat(json).contains("\"occurredAt\":\"2026-08-27T09:15:30Z\"");
    }

    @Test
    void keepsNullFieldsRatherThanOmittingThem() {
        // The whole reason this mapper is not the application mapper: a consumer must be able to
        // tell "field absent because the producer is older" from "field present and null".
        String json = EventJson.mapper()
            .writeValueAsString(new Payload(BigDecimal.ONE, Instant.EPOCH, null));

        assertThat(json).contains("\"note\":null");
    }

    @Test
    void ignoresUnknownPropertiesOnRead() {
        // Additive-only means a newer producer will send fields this consumer has never heard of.
        // Failing on them would make every additive change a breaking one.
        Payload p = EventJson.mapper().readValue(
            "{\"grandTotal\":\"5.00\",\"occurredAt\":\"2026-08-27T09:15:30Z\","
          + "\"note\":null,\"fieldFromANewerProducer\":42}", Payload.class);

        assertThat(p.grandTotal()).isEqualByComparingTo("5.00");
    }

    @Test
    void isASingleSharedInstance() {
        // Jackson 3 mappers are immutable and thread-safe, so one static final instance is correct:
        // no synchronisation, no per-call construction, no ThreadLocal pooling.
        assertThat(EventJson.mapper()).isSameAs(EventJson.mapper());
    }
}
```

- [ ] **Step 2: Run it to confirm it fails**

```bash
cd /Users/divyam/Documents/easy-crm/backend && ./gradlew :platform:platform-primitives:test --tests '*EventJsonTest' 2>&1 | tail -20
```

Expected: FAIL to compile — `cannot find symbol: class EventJson`.

- [ ] **Step 3: Write the minimal implementation**

Create `backend/platform/platform-primitives/src/main/java/com/easycrm/platform/money/EventJson.java`:

```java
package com.easycrm.platform.money;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The mapper for anything persisted or published — outbox {@code payload} JSONB, SNS, SQS.
 *
 * <p><b>This is deliberately not the application ObjectMapper, and the duplication is not an
 * accident.</b> The two wires have different owners and different change cadences. HTTP responses
 * are owned by Spring Boot and versioned by the API; an event payload is an additive-only contract
 * that must stay readable for years. Injecting the application mapper here would mean that setting
 * {@code spring.jackson.default-property-inclusion=non_null} to slim an API response silently drops
 * null fields from every subsequent event — after which a consumer cannot distinguish "field absent
 * because the producer is older" from "field present and null". {@code rebuild()} inherits the same
 * coupling one step later. If you are here to unify the two mappers, this paragraph is the reason
 * not to (MB4).
 *
 * <p>Every setting below is stated explicitly: this wire inherits nothing, so a change to Boot's
 * Jackson defaults cannot reach it.
 */
public final class EventJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            // Money and every other BigDecimal as a plain-notation string, never a JSON number.
            // This is TB3's structural fix (challenges #2 and #17).
            .addModule(new BigDecimalStringModule())
            // ISO-8601 timestamps, not epoch numbers: a number is ambiguous about its unit and a
            // stored event is read years later by something that cannot ask.
            .disable(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // Null fields are written, not omitted — see the class comment. Jackson's default is
            // ALWAYS; stated here so a future default change cannot alter the event contract.
            .changeDefaultPropertyInclusion(inc -> inc.withValueInclusion(
                    com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS))
            // An additive-only contract means a newer producer sends fields this consumer has never
            // heard of. Failing on them would make every additive change a breaking change.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private EventJson() {}

    /** The mapper for anything persisted or published. Immutable and thread-safe (Jackson 3). */
    public static JsonMapper mapper() {
        return MAPPER;
    }
}
```

**On the builder API:** Jackson 3 renamed several Jackson 2 builder methods, and the exact spellings
above are the plan's best reading rather than a verified fact. If any does not compile, find the
Jackson 3.1.4 equivalent (`javap -p` on `tools.jackson.databind.cfg.MapperBuilder` and
`tools.jackson.databind.json.JsonMapper$Builder` in the Gradle cache is the fastest way) and use it.
Do **not** drop a setting to make it compile — each one is a stated property of the event contract
and Step 1's test asserts it. If a setting turns out to be Jackson 3's default anyway, keep it: the
point is that this wire inherits nothing.

- [ ] **Step 4: Run the test to confirm it passes**

```bash
cd /Users/divyam/Documents/easy-crm/backend && ./gradlew :platform:platform-primitives:test --tests '*EventJsonTest' 2>&1 | tail -20
```

Expected: all five PASS.

- [ ] **Step 5: Answer the LLD's open question — enumerate what the two mappers disagree about**

LLD Appendix B item 3 asks whether `JsonMapper.builder()`'s defaults differ from the application
mapper Boot configures, and warns that without the enumeration, "inherits nothing" is aspirational.
Answer it with a test rather than prose, because prose goes stale.

Create `backend/src/test/java/com/easycrm/platform/money/EventJsonDivergenceTest.java` — it lives in
the **root** project because only there does a Boot-configured application mapper exist:

```java
package com.easycrm.platform.money;

import com.easycrm.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins where the event wire and the HTTP wire agree and where they diverge (LLD #1 Appendix B
 * item 3). "EventJson inherits nothing" is only meaningful if the differences are written down;
 * this is them, executable. A change to Boot's Jackson configuration that moves one of these
 * turns this test red, which is the point — the event contract must not drift silently behind an
 * API-shaping decision.
 */
class EventJsonDivergenceTest extends IntegrationTest {

    @Autowired ObjectMapper applicationMapper;

    record Sample(BigDecimal amount, Instant at, String absent) {}

    private static final Sample SAMPLE =
        new Sample(new BigDecimal("12.50"), Instant.parse("2026-08-27T09:15:30Z"), null);

    @Test
    void bothWiresAgreeThatMoneyIsAString() {
        // The one property that must never diverge. Both carry BigDecimalStringModule — the app
        // mapper via MoneyAutoConfiguration, the event mapper by explicit construction.
        assertThat(applicationMapper.writeValueAsString(SAMPLE)).contains("\"amount\":\"12.50\"");
        assertThat(EventJson.mapper().writeValueAsString(SAMPLE)).contains("\"amount\":\"12.50\"");
    }

    @Test
    void theEventWireIsNotDownstreamOfApiShapingDecisions() {
        // Whatever the application mapper is configured to do with nulls, the event wire keeps
        // them. If someone sets spring.jackson.default-property-inclusion=non_null tomorrow, the
        // app assertion below may change; the EventJson one must not.
        assertThat(EventJson.mapper().writeValueAsString(SAMPLE)).contains("\"absent\":null");
    }

    @Test
    void bothWiresWriteIso8601Timestamps() {
        assertThat(applicationMapper.writeValueAsString(SAMPLE)).contains("2026-08-27T09:15:30Z");
        assertThat(EventJson.mapper().writeValueAsString(SAMPLE)).contains("2026-08-27T09:15:30Z");
    }
}
```

Run it:

```bash
cd /Users/divyam/Documents/easy-crm/backend && ./gradlew test --tests '*EventJsonDivergenceTest' 2>&1 | tail -30
```

If an assertion about the **application** mapper fails, that is a real answer to Appendix B item 3,
not a broken test: correct the assertion to what Boot actually does and leave a comment saying so.
If an assertion about **`EventJson`** fails, fix `EventJson` — it is supposed to be the mapper whose
behaviour is decided here rather than inherited.

- [ ] **Step 6: Run the full suite**

```bash
cd /Users/divyam/Documents/easy-crm/backend && ./gradlew clean test 2>&1 | tail -5
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
```

Expected: `tests: 247` (239 + 5 + 3).

- [ ] **Step 7: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add -A
git commit -m "feat: EventJson, a mapper the event wire owns outright

Money now crosses two wires. HTTP responses belong to Spring Boot and are
versioned by the API; the outbox payload is an additive-only contract that
must stay readable for years. TB3 is what happens when the second one is
served by a mapper built ad hoc inside the outbox writer: no
BigDecimalStringModule, so money reaches SNS as an IEEE-754 double after the
entire stack avoided one.

Injecting the application mapper is the obvious fix and is worse. Setting
spring.jackson.default-property-inclusion=non_null to slim an API response
would silently drop null fields from every subsequent event, and a consumer
can then no longer tell 'absent because the producer is older' from 'present
and null'. rebuild() inherits the same coupling one step later.

EventJson states every setting explicitly, so the event wire inherits nothing.
One static final instance: Jackson 3 mappers are immutable and thread-safe.

EventJsonDivergenceTest answers the LLD's open question by pinning where the
two mappers agree and where they do not, executably rather than in prose.

247 tests."
```

---

## Task 5: The two rules that keep it honest

Being *able* to build a correct mapper is not the same as nobody building an incorrect one. R1
converts TB3 from a bug someone must catch in review into a build failure. R2 guards the module's
position at the bottom of the DAG — it is the rule that would have caught the P4 error (`Gstin`
importing `ValidationException` out of what was then `platform-web`) at build time rather than on
first read.

**Files:**
- Create: `backend/src/test/java/com/easycrm/arch/PlatformPrimitivesArchTest.java` (R1)
- Create: `backend/platform/platform-primitives/src/test/java/com/easycrm/platform/PrimitivesModuleArchTest.java` (R2)

**Interfaces:**
- Consumes: `EventJson` (Task 4), and the module layout from Task 1.
- Produces: nothing other tasks depend on.

**Why the two rules live in different projects.** R1 must see every class in the application to
judge who constructs a mapper, so it belongs beside `TenantScopingArchTest` in the root project. R2
must see **only** the module's own classes — and the subproject's test classpath contains exactly
those plus Jackson and JUnit, so scoping is structural rather than a filter someone can widen. That
matters here specifically: `com.easycrm.platform.error` is a split package (`ApiExceptionHandler`
is still in the root project), so a package-scoped R2 would try to judge a class that is not in this
module and fail for the wrong reason.

- [ ] **Step 1: Write R2 — the module depends on nothing**

Create `backend/platform/platform-primitives/src/test/java/com/easycrm/platform/PrimitivesModuleArchTest.java`:

```java
package com.easycrm.platform;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R2 — platform-primitives is the bottom of the DAG. A dependency edge out of it means something
 * has been placed wrong.
 *
 * <p>This test runs in the subproject, so the classpath it imports contains only this module's own
 * classes. That is deliberate: com.easycrm.platform.error is a split package — ApiExceptionHandler
 * stays in the application until platform-web exists — so a rule scoped by package name would try
 * to judge a class that is not part of this module. Scoping by classpath cannot be widened by
 * editing a filter.
 */
class PrimitivesModuleArchTest {

    private static JavaClasses moduleClasses() {
        return new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.easycrm");
    }

    @Test
    void theImportIsNotVacuous() {
        // ArchUnit 1.3.0 silently imported zero classes on Java 25 bytecode and passed every rule.
        // Never trust a green rule without this assertion.
        assertThat(moduleClasses()).as("imported classes").isNotEmpty();
    }

    @Test
    void dependsOnNoOtherPlatformModuleAndNoServicePackage() {
        ArchRule rule = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.easycrm.platform.web..",
                "com.easycrm.platform.tenancy..",
                "com.easycrm.platform.security..",
                "com.easycrm.platform.persistence..",
                "com.easycrm.platform.pdf..",
                "com.easycrm.platform.format..",
                "com.easycrm.catalog..",
                "com.easycrm.crm..",
                "com.easycrm.sales..",
                "com.easycrm.iam..",
                "com.easycrm.tenant..",
                "com.easycrm.demo..")
            .because("platform-primitives is the bottom of the DAG. An edge out of it is how P4's "
                   + "error happened — Gstin importing ValidationException out of platform-web "
                   + "would have dragged the servlet stack into notification-svc");

        rule.check(moduleClasses());
    }

    @Test
    void carriesNoRuntimeSpringDependency() {
        // spring-context and spring-boot-autoconfigure are compileOnly so notification-svc can
        // take this jar without inheriting a servlet stack. Only the auto-configuration may name
        // Spring at all, and it is inert when Spring is absent.
        ArchRule rule = noClasses()
            .that().haveSimpleNameNotEndingWith("AutoConfiguration")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
            .because("every type here must work with no Spring on the classpath");

        rule.check(moduleClasses());
    }
}
```

- [ ] **Step 2: Run R2 and confirm it passes on a non-empty import**

```bash
cd /Users/divyam/Documents/easy-crm/backend && ./gradlew :platform:platform-primitives:test --tests '*PrimitivesModuleArchTest' 2>&1 | tail -30
```

Expected: all three PASS. If `theImportIsNotVacuous` fails, the ArchUnit/Java 25 problem is back and
**every other rule in this build is meaningless** — stop and fix that first.

- [ ] **Step 3: Prove R2 can fail**

Temporarily add to `EventJson.java`:

```java
    private static final org.springframework.core.Ordered UNUSED = () -> 0;
```

Run again. Expected: `carriesNoRuntimeSpringDependency` FAILS naming `EventJson`. Then revert
(`git checkout -- platform/platform-primitives/src/main/java/com/easycrm/platform/money/EventJson.java`)
and re-run to confirm green.

- [ ] **Step 4: Write R1 — nobody outside the module builds a mapper**

Create `backend/src/test/java/com/easycrm/arch/PlatformPrimitivesArchTest.java`:

```java
package com.easycrm.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R1 — a JSON mapper built anywhere but platform-primitives loses BigDecimalStringModule, and money
 * reaches SNS as an IEEE-754 double with nothing thrown and nothing logged (TB3). Use
 * EventJson.mapper() for anything persisted or published, or inject Boot's for HTTP.
 *
 * <p>The condition is hand-written rather than expressed with callMethod(Class, String, Class...):
 * JsonMapper.builder() is overloaded, so a signature-based rule would silently cover only one
 * overload — the exact shape of vacuous pass this codebase has already been bitten by once.
 */
class PlatformPrimitivesArchTest {

    /** Types whose construction re-introduces TB3. */
    private static final Set<String> MAPPER_TYPES = Set.of(
        "tools.jackson.databind.ObjectMapper",
        "tools.jackson.databind.json.JsonMapper",
        "tools.jackson.databind.json.JsonMapper$Builder");

    private static JavaClasses appClasses() {
        return new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.easycrm");
    }

    @Test
    void theImportIsNotVacuous() {
        assertThat(appClasses()).as("imported classes").isNotEmpty();
    }

    @Test
    void noOneOutsidePlatformPrimitivesConstructsAJsonMapper() {
        ArchRule rule = noClasses()
            .that().resideOutsideOfPackage("com.easycrm.platform.money..")
            .should(constructAJsonMapper())
            .because("a mapper built elsewhere loses BigDecimalStringModule and sends money as a "
                   + "JSON number (TB3). Use EventJson.mapper(), or inject Boot's ObjectMapper");

        rule.check(appClasses());
    }

    private static ArchCondition<JavaClass> constructAJsonMapper() {
        return new ArchCondition<>("construct a JSON mapper") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaCodeUnit unit : item.getCodeUnits()) {
                    for (JavaMethodCall call : unit.getMethodCallsFromSelf()) {
                        if (MAPPER_TYPES.contains(call.getTargetOwner().getFullName())
                                && call.getName().equals("builder")) {
                            events.add(SimpleConditionEvent.violated(item, call.getDescription()));
                        }
                    }
                    for (JavaConstructorCall call : unit.getConstructorCallsFromSelf()) {
                        if (MAPPER_TYPES.contains(call.getTargetOwner().getFullName())) {
                            events.add(SimpleConditionEvent.violated(item, call.getDescription()));
                        }
                    }
                }
            }
        };
    }
}
```

- [ ] **Step 5: Run R1 and confirm it passes**

```bash
cd /Users/divyam/Documents/easy-crm/backend && ./gradlew test --tests '*PlatformPrimitivesArchTest' 2>&1 | tail -30
```

Expected: both PASS — no production class outside `com.easycrm.platform.money..` builds a mapper
today.

If it **fails**, do not weaken the rule: find the offending class and route it through
`EventJson.mapper()` or an injected `ObjectMapper`, then re-run. A genuine violation found here is
the rule doing its job on day one.

- [ ] **Step 6: Prove R1 can fail**

Temporarily add to any service class outside the money package — `CustomerService` is convenient:

```java
    private static final tools.jackson.databind.json.JsonMapper TB3 =
        tools.jackson.databind.json.JsonMapper.builder().build();
```

Run again. Expected: FAIL, naming `CustomerService`. Then revert
(`git checkout -- src/main/java/com/easycrm/crm/CustomerService.java`) and re-run to confirm green.

**Do not skip this step.** An ArchUnit rule that has never been seen to fail is indistinguishable
from one that imported zero classes — which is exactly what 1.3.0 did on this codebase.

- [ ] **Step 7: Run the full suite**

```bash
cd /Users/divyam/Documents/easy-crm/backend && ./gradlew clean test 2>&1 | tail -5
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
```

Expected: `tests: 252` (247 + 3 + 2).

- [ ] **Step 8: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add -A
git commit -m "test: ArchUnit rules R1 and R2 for platform-primitives

R1 fails the build if anything outside com.easycrm.platform.money constructs
a JSON mapper. That is TB3 — a mapper built ad hoc carries no
BigDecimalStringModule, so money reaches SNS as a double with nothing thrown
and nothing logged. Same move the codebase already makes for tenant scoping:
nobody hand-writes the dangerous thing, so nobody can forget the safe one.
The condition is hand-written because JsonMapper.builder() is overloaded and
a signature-based rule would cover one overload and pass vacuously.

R2 keeps the module at the bottom of the DAG. It runs in the subproject, so
its classpath contains only this module's classes — which matters because
com.easycrm.platform.error is split until platform-web exists, and a
package-scoped rule would judge ApiExceptionHandler and fail for the wrong
reason. It also asserts no type here depends on Spring outside the
auto-configuration, which is what lets notification-svc take the jar with no
servlet stack.

Both rules were confirmed to fail against a deliberate violation before being
trusted to pass, and both assert a non-empty import — ArchUnit 1.3.0 silently
imported zero classes on Java 25 bytecode and passed everything.

252 tests."
```

---

## Task 6: `Gstin.parse` validates the state prefix

Today `parse` validates length, charset and checksum but **not** the state prefix, so a GSTIN
beginning `00` or `39` passes as long as its check digit is consistent. `CustomerService`
compensates by calling `StateCode.requireValid(derived)` on the very next line — a two-step every
caller must remember, and the second caller (signup) did not. Folding the check in makes the type's
name true: a `Gstin` instance is a GSTIN that could exist.

This is a genuine behaviour change (MB6). Any stored GSTIN with an invalid state prefix was already
wrong, but Task 7 runs the audit before anything depends on that claim.

**Files:**
- Modify: `backend/platform/platform-primitives/src/main/java/com/easycrm/platform/gst/Gstin.java`
- Modify: `backend/platform/platform-primitives/src/test/java/com/easycrm/platform/gst/GstinTest.java`

**Interfaces:**
- Consumes: `Gstin`, `StateCode`, `ValidationException` (Task 1).
- Produces: `Gstin.parse(String)` now throws `ValidationException` on a checksum-valid GSTIN whose
  first two characters are not a valid GST state code. Task 7 relies on this.

- [ ] **Step 1: Write the failing tests**

Append to `GstinTest` in
`backend/platform/platform-primitives/src/test/java/com/easycrm/platform/gst/GstinTest.java`:

```java
    @Test
    void rejectsAChecksumValidGstinWithAnInvalidStatePrefix() {
        // "00" is not a GST state code. Before this check, such a GSTIN parsed cleanly as long as
        // its check digit was consistent, and CustomerService had to validate the prefix on the
        // next line — a two-step the signup path never performed (MF1).
        ValidationException ex = assertThrows(ValidationException.class,
            () -> Gstin.parse(withValidChecksum("00AAPFU0939F1Z")));
        assertTrue(ex.getFields().containsKey("gstin")
                || ex.getFields().containsKey("stateCode"));
    }

    @Test
    void rejectsNull() {
        assertThrows(ValidationException.class, () -> Gstin.parse(null));
    }

    @Test
    void rejectsFifteenCharactersOutsideTheCharset() {
        // Right length, wrong alphabet: lowercase is uppercased, but punctuation is not a GSTIN
        // character and must not reach the checksum step.
        assertThrows(ValidationException.class, () -> Gstin.parse("27AAPFU0939F1Z*"));
    }

    @Test
    void stateCodeAcceptsEveryValidBoundary() {
        assertTrue(StateCode.isValid("01"));
        assertTrue(StateCode.isValid("38"));
        assertTrue(StateCode.isValid("97"));   // Other Territory
        assertTrue(StateCode.isValid("99"));   // Centre Jurisdiction
        assertFalse(StateCode.isValid("00"));
        assertFalse(StateCode.isValid("39"));
        assertFalse(StateCode.isValid(null));
        assertFalse(StateCode.isValid("1"));
    }

    /**
     * Builds a valid 15th check character for a 14-character payload, so a test can construct a
     * GSTIN that is checksum-correct but state-invalid — the exact case this behaviour change
     * exists to reject, and one that cannot be written by hand.
     */
    private static String withValidChecksum(String payload14) {
        String charset = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        int factor = 2, sum = 0, cp = 36;
        for (int i = payload14.length() - 1; i >= 0; i--) {
            int d = factor * charset.indexOf(payload14.charAt(i));
            sum += (d / cp) + (d % cp);
            factor = (factor == 2) ? 1 : 2;
        }
        return payload14 + charset.charAt((cp - (sum % cp)) % cp);
    }
```

- [ ] **Step 2: Run to confirm the right one fails**

```bash
cd /Users/divyam/Documents/easy-crm/backend && ./gradlew :platform:platform-primitives:test --tests '*GstinTest' 2>&1 | tail -30
```

Expected: `rejectsAChecksumValidGstinWithAnInvalidStatePrefix` **FAILS** (no exception thrown — the
prefix is not checked). `rejectsNull`, `rejectsFifteenCharactersOutsideTheCharset` and
`stateCodeAcceptsEveryValidBoundary` should pass already; they close gaps in the existing five
cases rather than driving new behaviour.

- [ ] **Step 3: Fold the check into `parse`**

In `Gstin.java`, insert one line immediately before `return new Gstin(g);`:

```java
        // The state prefix is part of what makes a GSTIN a GSTIN. Validating it here rather than
        // leaving it to the caller closes MF1: CustomerService remembered the second step and the
        // signup path did not, and an invalid seller state code silently decides CGST+SGST vs IGST
        // on every quotation the tenant ever issues.
        StateCode.requireValid(g.substring(0, 2));
        return new Gstin(g);
```

- [ ] **Step 4: Run to confirm all pass**

```bash
cd /Users/divyam/Documents/easy-crm/backend && ./gradlew :platform:platform-primitives:test --tests '*GstinTest' 2>&1 | tail -20
```

Expected: all nine PASS.

- [ ] **Step 5: Run the full suite**

```bash
cd /Users/divyam/Documents/easy-crm/backend && ./gradlew clean test 2>&1 | tail -5
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
```

Expected: `tests: 256` (252 + 4), `failures: 0`.

If a **`crm`** test now fails, a customer fixture uses a checksum-valid GSTIN with an invalid state
prefix. Fix the fixture, not the check — but say so in the commit message, because it means such a
value was previously accepted by the API.

- [ ] **Step 6: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add -A
git commit -m "fix: Gstin.parse validates the state prefix

parse() checked length, charset and checksum but not the first two
characters, so a GSTIN beginning 00 or 39 parsed cleanly as long as its check
digit was consistent. CustomerService compensated by calling
StateCode.requireValid on the next line — a two-step every caller had to
remember, and the signup path never did.

Folding it in makes the type's name true: a Gstin instance is a GSTIN that
could exist. This is a real behaviour change (MB6), and intended — any stored
GSTIN with an invalid state prefix was already wrong.

Also closes gaps in GstinTest's original five cases: null, a right-length
string outside the charset, and every StateCode boundary (01, 38, 97, 99 and
the values either side).

256 tests."
```

---

## Task 7: The seller's GSTIN is validated too (MF1, MF2)

A *buyer's* GSTIN goes through `Gstin.parse` **and** `StateCode.requireValid` in `CustomerService`.
The *seller's* goes through neither: `SignupRequest` declares `@Pattern("\\d{2}")` on `stateCode`
and a bare `String gstin`. Since `QuotationService.isInterState` compares `tenant.getStateCode()`
against the customer's to choose **CGST+SGST vs IGST**, an invalid seller state code silently
decides the tax split of every quotation that tenant ever issues, and the unvalidated GSTIN prints
on every PDF letterhead.

MF2 first: folding `StateCode.requireValid` into `Gstin.parse` says nothing about rows already
stored. Audit before assuming.

**Files:**
- Modify: `backend/src/main/java/com/easycrm/iam/AuthService.java`
- Modify: `backend/src/main/java/com/easycrm/iam/web/dto/SignupRequest.java` (comment only)
- Modify: `backend/src/test/java/com/easycrm/iam/AuthServiceSignupTest.java`

**Interfaces:**
- Consumes: `Gstin.parse` with the state-prefix check (Task 6), `StateCode.requireValid` (Task 1).
- Produces: `AuthService.signup` throws `ValidationException` (→ 422 via `ApiExceptionHandler`) for
  an invalid seller `stateCode`, or a `gstin` that is present and malformed, or a `gstin` whose
  state prefix disagrees with the supplied `stateCode`.

- [ ] **Step 1: Run the MF2 audit before changing anything**

This is read-only and answers a question the plan cannot answer from source. Against a real
database, if one is available:

```sql
-- Any stored GSTIN whose state prefix is not a valid GST state code (01–38, 97, 99)?
SELECT 'tenant' AS src, id, gstin, state_code FROM tenant
 WHERE gstin IS NOT NULL
   AND substring(gstin, 1, 2) NOT IN (
       '01','02','03','04','05','06','07','08','09','10','11','12','13','14','15','16','17','18',
       '19','20','21','22','23','24','25','26','27','28','29','30','31','32','33','34','35','36',
       '37','38','97','99')
UNION ALL
SELECT 'customer', id, gstin, state_code FROM customer
 WHERE gstin IS NOT NULL
   AND substring(gstin, 1, 2) NOT IN (
       '01','02','03','04','05','06','07','08','09','10','11','12','13','14','15','16','17','18',
       '19','20','21','22','23','24','25','26','27','28','29','30','31','32','33','34','35','36',
       '37','38','97','99');

-- And the same question for state_code itself, which is what actually decides the tax split.
SELECT 'tenant' AS src, id, state_code FROM tenant
 WHERE state_code NOT IN (
       '01','02','03','04','05','06','07','08','09','10','11','12','13','14','15','16','17','18',
       '19','20','21','22','23','24','25','26','27','28','29','30','31','32','33','34','35','36',
       '37','38','97','99');
```

Run it as the **owner** role, not `easycrm_app` — this is a cross-tenant question and RLS will
otherwise return nothing and look like a clean result.

**There is no production deployment and no shared database**, so the expected practical outcome is
that there is nothing to audit. Record the outcome either way in the commit message: "audited, N
rows" or "no deployed database exists; the audit is recorded here so the next person does not
re-derive the question." **Do not write a data migration** — if rows are found, that is a finding
for the handoff and a separate decision, not part of this task.

- [ ] **Step 2: Write the failing tests**

Add to `backend/src/test/java/com/easycrm/iam/AuthServiceSignupTest.java`. Note the existing helper
returns `new SignupRequest(slug, "Acme Traders", "27", null, email, null, "hunter2pass")` — the
argument order is `(slug, businessName, stateCode, gstin, email, phone, password)`:

```java
    @Test
    void rejectsAnInvalidSellerStateCode() {
        // "39" passes @Pattern("\\d{2}") and is not a GST state code. It silently decides
        // CGST+SGST vs IGST on every quotation this tenant ever issues (MF1).
        SignupRequest req = new SignupRequest(
            "bad-state-" + UUID.randomUUID(), "Acme Traders", "39", null,
            "bad-state-" + UUID.randomUUID() + "@example.com", null, "hunter2pass");

        ValidationException ex = assertThrows(ValidationException.class, () -> auth.signup(req));
        assertTrue(ex.getFields().containsKey("stateCode"));
    }

    @Test
    void rejectsAMalformedSellerGstin() {
        SignupRequest req = new SignupRequest(
            "bad-gstin-" + UUID.randomUUID(), "Acme Traders", "27", "27AAPFU0939F1ZZ",
            "bad-gstin-" + UUID.randomUUID() + "@example.com", null, "hunter2pass");

        ValidationException ex = assertThrows(ValidationException.class, () -> auth.signup(req));
        assertTrue(ex.getFields().containsKey("gstin"));
    }

    @Test
    void rejectsASellerGstinThatDisagreesWithTheStateCode() {
        // 27… is Maharashtra; the seller claims 29 (Karnataka). One of the two is wrong and the
        // system must not pick silently — this is the same rule CustomerService already applies
        // to a buyer.
        SignupRequest req = new SignupRequest(
            "mismatch-" + UUID.randomUUID(), "Acme Traders", "29", "27AAPFU0939F1ZV",
            "mismatch-" + UUID.randomUUID() + "@example.com", null, "hunter2pass");

        ValidationException ex = assertThrows(ValidationException.class, () -> auth.signup(req));
        assertTrue(ex.getFields().containsKey("stateCode"));
    }

    @Test
    void acceptsAValidSellerGstin() {
        SignupRequest req = new SignupRequest(
            "good-gstin-" + UUID.randomUUID(), "Acme Traders", "27", "27AAPFU0939F1ZV",
            "good-gstin-" + UUID.randomUUID() + "@example.com", null, "hunter2pass");

        assertNotNull(auth.signup(req).tenantId());
    }
```

Add whatever imports the file is missing (`java.util.UUID`,
`com.easycrm.platform.error.ValidationException`, the JUnit assertions). Match the surrounding
style: check how existing tests in this class obtain `auth` and construct unique slugs/emails, and
follow it rather than the sketch above if they differ.

- [ ] **Step 3: Run to confirm they fail**

```bash
cd /Users/divyam/Documents/easy-crm/backend && ./gradlew test --tests '*AuthServiceSignupTest' 2>&1 | tail -40
```

Expected: the first three FAIL (signup succeeds; no exception thrown). `acceptsAValidSellerGstin`
passes already — it is the guard that the fix does not over-reject.

- [ ] **Step 4: Validate the seller at signup**

In `AuthService.signup`, insert immediately after the slug-conflict check and **before** the
`new Tenant(...)` call:

```java
        // MF1: a buyer's GSTIN goes through Gstin.parse and StateCode.requireValid in
        // CustomerService; the seller's went through neither. QuotationService.isInterState
        // compares tenant.stateCode against the customer's to choose CGST+SGST vs IGST, so an
        // invalid seller state code silently decides the tax split of every quotation this tenant
        // ever issues — and the unvalidated GSTIN prints on every PDF letterhead.
        String stateCode = req.stateCode();
        if (req.gstin() != null && !req.gstin().isBlank()) {
            String derived = Gstin.parse(req.gstin()).stateCode();   // 422 on checksum or prefix
            if (!derived.equals(stateCode)) {
                throw new ValidationException("stateCode", "must match the GSTIN state code");
            }
        }
        StateCode.requireValid(stateCode);
```

Add the imports `com.easycrm.platform.gst.Gstin`, `com.easycrm.platform.gst.StateCode` and
`com.easycrm.platform.error.ValidationException` if the file lacks them.

**Deliberately narrower than `CustomerService.resolveGstinAndState`:** that method treats a blank
`stateCode` as "derive it from the GSTIN", because a buyer may be entered either way.
`SignupRequest.stateCode` is `@NotBlank`, so it is always present and there is nothing to derive —
adding a derivation branch here would be dead code. The mismatch check is what makes the two paths
agree on the question that matters.

- [ ] **Step 5: Note where the validation actually lives**

In `SignupRequest.java`, amend the `stateCode` line's message so the annotation does not read as the
whole story:

```java
    // @Pattern is shape only. AuthService.signup runs StateCode.requireValid, and Gstin.parse when
    // a GSTIN is supplied — "two digits" is not the same as "a GST state code" (MF1).
    @NotBlank @Pattern(regexp = "\\d{2}", message = "stateCode must be 2 digits") String stateCode,
```

- [ ] **Step 6: Run to confirm all pass**

```bash
cd /Users/divyam/Documents/easy-crm/backend && ./gradlew test --tests '*AuthServiceSignupTest' 2>&1 | tail -20
```

Expected: all PASS, including the pre-existing cases in that class.

- [ ] **Step 7: Run the full suite**

```bash
cd /Users/divyam/Documents/easy-crm/backend && ./gradlew clean test 2>&1 | tail -5
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'failures="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "failures:", s}'
```

Expected: `tests: 260` (256 + 4), `failures: 0`.

Every existing signup fixture uses `"27"` with a `null` GSTIN, so none should break. If one does,
read it before changing it — a fixture using an invalid state code is a small piece of evidence
about what the API used to accept.

- [ ] **Step 8: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add -A
git commit -m "fix: validate the seller's GSTIN and state code at signup (MF1)

A buyer's GSTIN went through Gstin.parse and StateCode.requireValid in
CustomerService. The seller's went through neither — SignupRequest declared
@Pattern(\"\\\\d{2}\") on stateCode and a bare String gstin.

That asymmetry is not cosmetic. QuotationService.isInterState compares
tenant.stateCode against the customer's to choose CGST+SGST vs IGST, so an
invalid seller state code silently decides the tax split of every quotation
the tenant ever issues, and the unvalidated GSTIN prints on every PDF
letterhead. Nothing failed and nothing logged.

signup() now parses a supplied GSTIN, requires its state prefix to agree with
the declared state code, and validates the state code itself. Narrower than
CustomerService by design: SignupRequest.stateCode is @NotBlank, so there is
never anything to derive.

MF2's audit query for already-stored GSTINs with invalid state prefixes was
run first; see the plan's Task 7 step 1.

260 tests."
```

---

## Task 8: Close the loop

The module is built. This task makes the next person's life possible: the challenge log, the LLD's
status, and the handoff that points at what to do next.

**Files:**
- Modify: `docs/superpowers/engineering-challenges.md`
- Modify: `docs/architecture/2026-08-26-platform-primitives-lld.md`
- Modify: `docs/superpowers/HANDOFF.md`
- Modify: `docs/superpowers/annotations-reference.md` (only if Task 2's row was missed)
- Modify: `docs/superpowers/specs/2026-08-26-shared-platform-modules-design.md`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing code depends on.

- [ ] **Step 1: Sweep for unlogged challenges**

Re-read what actually happened in Tasks 1–7 and ask the `CLAUDE.md` question: *did we solve anything
non-obvious that is not yet logged?* Candidates this plan expects to have earned an entry — log the
ones that turned out to be real, and skip any that turned out to be routine:

- **The two-wire mapper problem.** Why an event payload does not get the application's
  `ObjectMapper` — the `default-property-inclusion` example, why `rebuild()` inherits the same
  coupling, and how R1 converts the trap into a build failure. This is the strongest candidate and
  is worth writing well; it is the module's whole reason for existing.
- **An auto-configuration class inside the application's own component-scan range** — reachable by
  two mechanisms at once, and what Spring actually does about it (Task 2 step 6 found out).
- **Scoping an ArchUnit rule by classpath rather than by package**, because a package split across
  two Gradle modules cannot be judged by name (Task 5).
- **Whatever Task 4 step 5 discovered** about Jackson 3's builder defaults versus Boot's configured
  mapper, if the answer was surprising.

Use the template at the bottom of the file: Problem → why it's hard → Solution → Lesson. Number
from the current highest (challenge #31 is the last one; start at #32). Quality over volume — two
good entries beat four thin ones, and routine build configuration does not qualify.

- [ ] **Step 2: Mark the LLD implemented**

At the top of `docs/architecture/2026-08-26-platform-primitives-lld.md`, change the status line:

```markdown
**Status:** **IMPLEMENTED** — merged as <merge commit>. Design-time status was "Design only; zero
code changed" at baseline `80e74a3`.
```

Then walk its Appendix B and replace each "to verify" item with the answer this slice produced —
the Jackson auto-configuration artifact (`spring-boot-jackson`, class
`org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration`, which injects a
`Collection<JacksonModule>`), whether `JacksonModule` beans from an auto-configuration are
discovered, the `JsonMapper.builder()` defaults, ArchUnit's ability to express R1, and whether `api`
on jackson-databind leaked anything. Items that were **not** settled must say so plainly rather than
being quietly dropped. Add findings for anything new (MF7 onward).

- [ ] **Step 3: Update the parent spec**

In `docs/superpowers/specs/2026-08-26-shared-platform-modules-design.md`, Part 7, change module 1's
status from **written** to **built**, naming the merge commit. Add MF3's missing edge if it is still
absent: `platform-web` depends on `platform-primitives`, which the Part 1 diagram does not show.

- [ ] **Step 4: Rewrite `HANDOFF.md` for the new state**

This is the document a fresh agent reads first, and it currently says "nothing is in flight" and
"the build is still one project under `backend/`". Both become false. Update at minimum:

- The **"Last updated"** header — first code change since `8b6644b`; the build is now multi-project.
- **§0 item 1** — the baseline is no longer "231 tests" and no longer a single-project count. Put
  the new number and the two-project counting command in.
- **§3 Current state** — a bullet for this slice: what was delivered, the merge commit, the new test
  count. The uncommitted-files bullet is now stale (they were committed as `ac4eaca`); remove it.
- **§8** — the platform-module track is now live, not design-only. Record that
  `platform-primitives` is built and that the next module by dependency order is `platform-web`
  (LLD #2, `docs/architecture/2026-08-26-platform-web-lld.md`), while noting the three findings in
  the platform-LLDs handoff §3 that outrank the module queue — PF14 (RLS is `ENABLE`d and never
  `FORCE`d), PF15 (nothing guards layer 3), PF19 — and backlog item 3's rate limiting, which two
  independent arguments now favour pulling forward.
- **§2** — add this plan and the LLD to the numbered reading list.

- [ ] **Step 5: Verify the docs against reality**

Do not trust the numbers you just wrote:

```bash
cd /Users/divyam/Documents/easy-crm/backend && ./gradlew clean test 2>&1 | tail -5
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'tests="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "tests:", s}'
find . -path '*/build/test-results/test/*.xml' -exec grep -ho 'failures="[0-9]*"' {} + \
  | sed 's/[^0-9]//g' | awk '{s+=$1} END {print "failures:", s}'
```

Every count written into `HANDOFF.md` must be the number this command just printed. If they differ,
the docs are wrong — fix the docs.

- [ ] **Step 6: Commit**

```bash
cd /Users/divyam/Documents/easy-crm
git add -A
git commit -m "docs: platform-primitives is built; challenges, LLD status, handoff

LLD #1 moves from 'design only' to implemented, with its Appendix B items
replaced by the answers this slice actually produced rather than quietly
dropped. Parent spec Part 7 marks module 1 built.

HANDOFF.md was written for a single-project build with nothing in flight;
both are now false. Updates the baseline command (the count spans two
projects), records what this slice delivered, and points §8 at platform-web
as the next module by dependency order — while keeping PF14, PF15 and the
rate-limiting argument above the module queue, where the LLD thread put them.

New engineering challenges logged for the problems this slice actually solved."
```

- [ ] **Step 7: Finish the branch**

Use the **superpowers:finishing-a-development-branch** skill to decide how this integrates. Before
invoking it, confirm the state it will assume:

```bash
cd /Users/divyam/Documents/easy-crm && git status --short && git log --oneline main..HEAD
```

Expected: a clean tree and eight commits.

---

## Self-Review

**Spec coverage** — every section of LLD #1 mapped to a task:

| LLD section | Task |
|---|---|
| Part 0 (error types sink here, module renamed) | 1 |
| Part 1 (file layout, packages unchanged) | 1 |
| 1.1 build file (`api` jackson, `compileOnly` Boot) | 1 |
| 1.2 auto-configuration + `AutoConfiguration.imports` | 2 |
| 2.1 error vocabulary moved | 1 |
| 2.2 `BigDecimalStringModule` unchanged | 1 (moved), 3 (tested) |
| 2.3 `EventJson`, the two wires | 4 |
| 2.4 `Gstin` state-prefix check | 6 |
| 2.5 `StateCode` unchanged | 1 (moved), 6 (boundary tests) |
| Part 3 (how a service adopts it) | 1 step 6 |
| Part 4 R1 | 5 |
| Part 4 R2 | 5 |
| Part 4 R3 (existing D12 rule, unchanged) | — no change needed |
| Part 5 test plan 5.1 (keep `MoneyWireFormatTest`) | 2 step 7 |
| Part 5 test plan 5.2 (all unit rows) | 3, 4, 6 |
| Part 6 MB1 | 2 |
| Part 6 MB2 | 4, 5 |
| Part 6 MB3/MB4 (comments in source, not only the doc) | 3, 4 |
| Part 6 MB5 | 5 (R2) |
| Part 6 MB6 | 6 |
| MF1 | 7 |
| MF2 | 7 step 1 |
| MF3 (parent spec edge) | 8 step 3 |
| MF4 (`MoneyWireFormatTest` tripwire dependency) | 2 step 7 |
| MF5 (no unit test of the serialiser) | 3 |
| MF6 (`Gstin` hard-codes the field name) | — accepted as-is by the LLD; revisit when a second GSTIN field exists |
| Appendix B 1–2 (Boot 4 Jackson artifact, module discovery) | resolved during planning; recorded in 8 step 2 |
| Appendix B 3 (`JsonMapper.builder()` defaults) | 4 step 5 |
| Appendix B 4 (ArchUnit can express R1) | 5 steps 4–6 |
| Appendix B 5 (`WRITE_NUMBERS_AS_STRINGS` default) | 3 — the tests assert exact output, so a stringify-everything default would show up as `totalElements` regressions in the root suite |
| Appendix B 6 (`api` leakage) | 1 step 3 comment; confirmed by 8 step 2 |

**Not in scope, deliberately:** `PdfEngine` and `IndianFormats` leaving for `document-svc` (P5),
`JwtService.mint` leaving for `identity-svc` (P6), `DemoRecord` to test fixtures — all are Part 5 of
the *parent* spec, not LLD #1, and each belongs to the module that receives it.

**Placeholder scan:** no "TBD", no "add appropriate error handling", no "similar to Task N". Two
steps deliberately delegate a decision rather than pre-deciding it — Task 4 step 3's Jackson 3
builder spellings and Task 8 step 1's choice of which challenges to log — and both state the
criterion and the fallback rather than leaving it open.

**Type consistency:** `EventJson.mapper()` returns `tools.jackson.databind.json.JsonMapper`
throughout (Tasks 4, 5). `Gstin.parse(String) → Gstin` and `Gstin.stateCode() → String` are used
consistently (Tasks 6, 7). `StateCode.requireValid(String) → void` and
`StateCode.isValid(String) → boolean` match the existing source. `ValidationException.getFields()
→ Map<String, String>` matches. `SignupRequest`'s seven-argument order
`(slug, businessName, stateCode, gstin, email, phone, password)` is used identically in every Task 7
fixture and matches the record as it exists today.

**Running test count:** 231 → 233 (T2) → 239 (T3) → 247 (T4) → 252 (T5) → 256 (T6) → 260 (T7).
Task 1 must hold at 231; any other number there means a source set is not being run.
