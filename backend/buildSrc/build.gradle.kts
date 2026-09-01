plugins { `kotlin-dsl` }

repositories { gradlePluginPortal() }

// A precompiled script plugin can only apply plugins whose implementation is on the
// buildSrc classpath, so each one is declared here as an ordinary dependency using its
// *marker* coordinate (id:id.gradle.plugin).
dependencies {
    implementation("com.diffplug.spotless:spotless-plugin-gradle:${libs.versions.spotless.get()}")
    implementation("com.github.spotbugs:com.github.spotbugs.gradle.plugin:${libs.versions.spotbugs.get()}")
}
