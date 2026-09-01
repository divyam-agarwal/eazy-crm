plugins {
    java
    id("easycrm.quality-conventions")
    alias(libs.plugins.springBoot)
    alias(libs.plugins.springDependencyManagement)
}

group = "com.easycrm"
version = "0.0.1-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

// Generates META-INF/build-info.properties from the Gradle project version, exposed at
// runtime as the actuator's BuildProperties bean. OpenApiConfig reads the API document's
// info.version from it, so the Gradle version is the single source of truth -- a literal in
// application.yml would be a second copy to keep in sync by hand, which is exactly what the
// version catalog's comments exist to prevent.
springBoot { buildInfo() }

repositories { mavenCentral() }

dependencies {
    // The bottom of the platform DAG: exception vocabulary, money wire format, GST value
    // types. A declared edge rather than a package convention — see LLD #1 and TB3.
    implementation(project(":platform:platform-primitives"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Spring Boot 4 split auto-config into per-integration modules: flyway-core alone
    // no longer provides FlywayAutoConfiguration. The starter bundles the auto-config module.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    // XHTML -> PDF, pure Java (PDFBox backend). No external binary, so CI and
    // Testcontainers need nothing extra installed.
    // As of 2026-07-28, 1.1.x was never cut upstream (latest on Maven Central: 1.0.10,
    // dev branch pom.xml reads 1.0.11-SNAPSHOT) -- 1.0.10 is the actual latest.
    implementation(libs.openhtmltopdf.pdfbox)

    implementation(libs.jjwt.api)
    runtimeOnly(libs.jjwt.impl)
    runtimeOnly(libs.jjwt.jackson)

    // Token-bucket rate limiting. The artifact id is JDK-qualified as of 8.10:
    // com.bucket4j:bucket4j-core is a stale coordinate that resolves to 8.1.x.
    implementation(libs.bucket4j.core)
    // Bounded bucket storage. The key is a client IP — attacker-controlled — so an
    // unbounded map would make the rate limiter its own memory-exhaustion vector.
    // Version comes from the Boot BOM; do not pin it here.
    implementation("com.github.ben-manes.caffeine:caffeine")

    // OpenAPI generation. The generator ships; the browsable UI does not.
    // springdoc 3.x is the Spring Boot 4 line -- see the catalog comment. 2.x is Boot 3.
    implementation(libs.springdoc.webmvc.api)
    // developmentOnly is Spring Boot's own configuration: on the bootRun classpath,
    // excluded from bootJar. The swagger-ui webjar therefore never reaches a production
    // artefact even if someone later flips springdoc.swagger-ui.enabled by mistake --
    // structural absence rather than a configured one.
    developmentOnly(libs.springdoc.webmvc.ui)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    // Boot 4 split MockMvc test auto-config (@AutoConfigureMockMvc) into its own module;
    // spring-boot-starter-test no longer brings it.
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    // Spring Boot 4.1 BOM does not manage Testcontainers module versions here — pin the BOM.
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    // 1.4.x supports parsing Java 25 bytecode; 1.3.0 silently skips it (imports 0 classes).
    testImplementation(libs.archunit.junit5)
}

tasks.withType<Test> { useJUnitPlatform() }
