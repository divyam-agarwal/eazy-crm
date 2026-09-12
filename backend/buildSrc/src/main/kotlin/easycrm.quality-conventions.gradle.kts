// Shared quality gates for every EasyCRM Gradle project.
//
// Applied explicitly by each real project rather than through allprojects{}: settings
// includes ":platform:platform-primitives", which materialises an implicit empty
// ":platform" project that allprojects{} would wrongly configure, while subprojects{}
// would miss the root project where all 198 main sources live.
//
// KNOWN GRADLE LIMITATION: type-safe `libs.versions.x.get()` accessors are NOT available
// inside a precompiled script plugin — those are generated for the *including* build via
// the `versionCatalogs {}` block in its own settings.gradle.kts, and this file is compiled
// as part of a separate buildSrc build that predates that generation step. The untyped
// lookup below (`VersionCatalogsExtension`, obtained via `extensions.getByType`) is NOT
// subject to that limitation — buildSrc's own settings.gradle.kts registers a catalog
// named "libs" pointing at the same gradle/libs.versions.toml (see buildSrc/settings.gradle.kts),
// and that catalog is visible to this plugin at configuration time through the untyped API.
// This is the documented workaround: no type safety on the key name, but a single
// TOML-sourced value with no hand-kept duplicate.
val libsCatalog = extensions.getByType(org.gradle.api.artifacts.VersionCatalogsExtension::class.java).named("libs")
val palantirJavaFormatVersion = libsCatalog.findVersion("palantirJavaFormat").get().requiredVersion
val findSecBugsVersion = libsCatalog.findVersion("findsecbugs").get().requiredVersion

plugins {
    java
    jacoco
    id("com.diffplug.spotless")
    id("com.github.spotbugs")
    id("org.owasp.dependencycheck")
}

spotless {
    java {
        // Only this project's own sources. target must be explicit: the default sourceSet
        // scan would also reach generated output under build/.
        target("src/**/*.java")
        // 4-space, 120-col -- the closest match to the style already in the tree, chosen
        // to keep the one-time reformat diff as small as a whole-tree reformat can be.
        //
        // Sourced from gradle/libs.versions.toml (`palantirJavaFormat`) via the untyped
        // VersionCatalogsExtension lookup at the top of this file -- see the comment there
        // for why the type-safe `libs.versions.x.get()` form doesn't work here. The catalog
        // is the single source of truth; nothing to keep in sync by hand.
        palantirJavaFormat(palantirJavaFormatVersion)
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        if (project == rootProject) {
            // Only the root project's application of this convention plugin reaches
            // buildSrc: it is a sibling build rooted at the same directory as the root
            // project, has no Spotless of its own, and would otherwise sit outside every
            // gate this slice adds -- including the three files this slice is *about*
            // (this file, buildSrc/build.gradle.kts, buildSrc/settings.gradle.kts).
            target("*.gradle.kts", "buildSrc/*.gradle.kts", "buildSrc/src/main/kotlin/*.gradle.kts")
        } else {
            target("*.gradle.kts")
        }
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
    // Sourced from gradle/libs.versions.toml (`findsecbugs`) via the untyped
    // VersionCatalogsExtension lookup at the top of this file, for the same reason
    // palantirJavaFormatVersion is above: the type-safe `libs.versions.x.get()` accessors
    // don't exist inside a precompiled script plugin, but the untyped catalog lookup does.
    "spotbugsPlugins"("com.h3xstream.findsecbugs:findsecbugs-plugin:$findSecBugsVersion")
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

// Published-CVE scanning over the dependency tree. Two things about this are deliberate.
//
// It is NOT wired into `check` (spec D7). Every other gate in this file runs on every build;
// this one would put an NVD database sync in front of it. Invoke it explicitly:
//     ./gradlew dependencyCheckAggregate
//
// And failBuildOnCVSS is set above the maximum possible score, so it never fails the build even
// when invoked directly -- matching the CI job's continue-on-error (spec D1). The flip trigger
// for both is branch protection: at that point a finding lands in a PR queue with someone to
// triage it, rather than on a main branch that deploys itself.
dependencyCheck {
    // CVSS v3 tops out at 10.0. 11 means "never fail", which is the documented idiom.
    failBuildOnCVSS = 11f
    formats = listOf("HTML", "JSON")

    // Two sources, one wiring. CI supplies the environment variable from the repository secret;
    // a local run supplies the Gradle property from ~/.gradle/gradle.properties, which is
    // outside the repository and therefore outside both git and gitleaks.
    //
    // Blank-safe deliberately: an unconfigured GitHub secret can still materialise NVD_API_KEY
    // as an empty string, which `Provider.isPresent` sees as present -- so a naive check would
    // skip straight past this diagnostic into the plugin's own opaque failure with no hint why.
    val nvdKey = providers.gradleProperty("nvdApiKey")
        .orElse(providers.environmentVariable("NVD_API_KEY"))
        .orElse("")
        .map { it.trim() }
    if (nvdKey.get().isNotEmpty()) {
        nvd { apiKey = nvdKey.get() }
    } else {
        // NOT graceful degradation: plugin 13.0.0's NVD API 2.0 client rejects an unauthenticated
        // request outright (NvdApiException: Invalid API Key) rather than falling back to a slow
        // anonymous sync -- proved by running dependencyCheckAggregate with no key configured.
        // Set nvdApiKey in ~/.gradle/gradle.properties for a local run, or the NVD_API_KEY
        // repository secret for CI.
        logger.lifecycle(
            "No nvdApiKey property or NVD_API_KEY env var (or it is blank) - the OWASP scan below " +
                "will FAIL outright, not run slowly. Set nvdApiKey in ~/.gradle/gradle.properties " +
                "locally, or the NVD_API_KEY repository secret in CI."
        )
    }
}
