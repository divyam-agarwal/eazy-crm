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
    implementation("com.openhtmltopdf:openhtmltopdf-pdfbox:1.0.10")

    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    // Boot 4 split MockMvc test auto-config (@AutoConfigureMockMvc) into its own module;
    // spring-boot-starter-test no longer brings it.
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    // Spring Boot 4.1 BOM does not manage Testcontainers module versions here — pin the BOM.
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.3"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    // 1.4.x supports parsing Java 25 bytecode; 1.3.0 silently skips it (imports 0 classes).
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
}

tasks.withType<Test> { useJUnitPlatform() }
