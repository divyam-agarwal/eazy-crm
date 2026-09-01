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
    id("com.diffplug.spotless")
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

// SpotBugs config arrives in Task 4, JaCoCo in Task 5.
