// buildSrc is a separate Gradle build and does not inherit the main build's version
// catalog. Pointing it at the same TOML keeps one source of versions across both.
dependencyResolutionManagement {
    repositories { gradlePluginPortal() }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}
