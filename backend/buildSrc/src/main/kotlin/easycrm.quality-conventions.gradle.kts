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
}

// Spotless config arrives in Task 2, SpotBugs in Task 4, JaCoCo in Task 5.
