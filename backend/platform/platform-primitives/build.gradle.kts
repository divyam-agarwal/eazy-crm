plugins {
    `java-library`
    id("easycrm.quality-conventions")
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
    api(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))

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

    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    // 1.4.x parses Java 25 bytecode; 1.3.0 silently skips it and passes vacuously.
    testImplementation(libs.archunit.junit5)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> { useJUnitPlatform() }
