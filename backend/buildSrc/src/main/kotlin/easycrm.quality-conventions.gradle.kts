// Shared quality gates for every EasyCRM Gradle project.
//
// Applied explicitly by each real project rather than through allprojects{}: settings
// includes ":platform:platform-primitives", which materialises an implicit empty
// ":platform" project that allprojects{} would wrongly configure, while subprojects{}
// would miss the root project where all 198 main sources live.
//
// KNOWN GRADLE LIMITATION: type-safe `libs.` accessors are NOT available inside a
// precompiled script plugin. Versions needed at *configuration* time here (find-sec-bugs)
// are literals with a comment, not catalog references. Do not spend time trying to make
// `libs` work in this file — it does not, by design.

plugins {
    java
    jacoco
    id("com.diffplug.spotless")
    id("com.github.spotbugs")
}

spotless {
    java {
        // Only this project's own sources. target must be explicit: the default sourceSet
        // scan would also reach generated output under build/.
        target("src/**/*.java")
        // 4-space, 120-col -- the closest match to the style already in the tree, chosen
        // to keep the one-time reformat diff as small as a whole-tree reformat can be.
        //
        // Pinned to the same value as libs.versions.palantirJavaFormat in the TOML
        // (2.97.0). Type-safe `libs.` accessors don't work inside a precompiled script
        // plugin (see the known-limitation comment at the top of this file), so this is a
        // literal that must be kept in sync with the catalog by hand.
        palantirJavaFormat("2.97.0")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("yaml") {
        // NOTE the exclusions this does NOT need: only src/main/resources/*.yml is matched,
        // so db/migration/*.sql and templates/quotation.xhtml are out of scope by construction
        // rather than by an exclude rule someone could later delete. See plan Global Constraints.
        target("src/**/*.yml")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

spotbugs {
    effort = com.github.spotbugs.snom.Effort.MAX
    reportLevel = com.github.spotbugs.snom.Confidence.DEFAULT
    // Every pre-existing finding is captured in baseline.xml; only NEW findings fail the
    // build now.
    ignoreFailures = false
    excludeFilter = rootProject.file("config/spotbugs/exclude.xml")
    // SpotBugs Gradle plugin 6.5.11 wires this to the underlying `-excludeBugs` flag:
    // findings whose instanceHash matches an entry here are suppressed from the
    // pass/fail decision (they still show up in the HTML/XML reports). One shared file
    // covering both projects' pre-existing findings -- a hash from one project simply
    // never matches anything in the other project's analysis.
    baselineFile = rootProject.file("config/spotbugs/baseline.xml")
}

dependencies {
    // find-sec-bugs is the half that earns SpotBugs its place in this codebase: JWT
    // mint/parse, a bcrypt password path, a permitAll route that renders a PDF, and a
    // rate limiter keyed on an attacker-controlled value.
    //
    // Literal version, not libs.findsecbugs: type-safe catalog accessors do not exist
    // inside a precompiled script plugin. Keep it equal to `findsecbugs` in
    // gradle/libs.versions.toml.
    "spotbugsPlugins"("com.h3xstream.findsecbugs:findsecbugs-plugin:1.14.0")
}

// Test sources are excluded as noise: assertion-heavy test code trips a large number of
// low-value patterns and drowns the signal from main.
tasks.named("spotbugsTest") { enabled = false }

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports.create("html") { required = true }
    reports.create("xml") { required = true }
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        // HTML is what a human reads on a failed build; XML is what SonarCloud would
        // consume if the deferral in the design spec section 3 is ever revisited.
        html.required = true
        xml.required = true
    }
}

tasks.named("test") { finalizedBy(tasks.named("jacocoTestReport")) }

// Coverage floors, per project (spec D10): the root project (496 tests over a Spring
// app with Testcontainers) and platform-primitives (23 tests over pure value types) have
// genuinely different character, so one blended number would mask movement in either.
// jacoco-report-aggregation is deliberately NOT applied -- these stay two independent
// verification runs, not one merged report.
//
// Each pair is: round the value measured on 2026-09-01 in the build-hygiene slice DOWN
// to the nearest whole percent, then subtract 1, so a marginally unlucky run does not
// turn the build red. Floors ratchet UP only -- raise them when coverage rises; never
// lower one to go green.
val (lineFloor, branchFloor) = if (project.name == "platform-primitives") {
    // Measured LINE 84.1%, BRANCH 100.0%.
    "0.83".toBigDecimal() to "0.99".toBigDecimal()
} else {
    // Root project ("easycrm-backend"). Measured LINE 93.9%, BRANCH 82.3%.
    "0.92".toBigDecimal() to "0.81".toBigDecimal()
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("jacocoTestReport"))
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = lineFloor
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = branchFloor
            }
        }
    }
}

// JaCoCo does not wire verification into check by default, unlike Spotless and SpotBugs.
tasks.named("check") { dependsOn(tasks.named("jacocoTestCoverageVerification")) }
