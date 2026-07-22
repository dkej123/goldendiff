// Tool-agnostic core: golden matching, git access, pixel diff, project file indexing.
//
// The one rule that matters here: this module must never depend on the IntelliJ Platform, on Swing, or
// on Compose. It is consumed both by the IDE plugins and by the standalone desktop app, so anything
// IDE-specific belongs in :public-plugin and anything visual belongs in :core-ui.
//
// Kept on plain kotlin-jvm rather than Kotlin Multiplatform on purpose: every consumer is a JVM, so
// KMP would only buy expect/actual ceremony around File, BufferedImage, ImageIO and git. It would also
// be unusable from the plugin side - the IntelliJ Platform Gradle Plugin looks for `compileKotlin`/`jar`
// while KMP produces `compileKotlinJvm`/`jvmJar`, and silently builds a plugin ZIP with no Kotlin
// classes in it (intellij-platform-gradle-plugin#1507).
plugins {
    id("org.jetbrains.kotlin.jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    // The root sets `kotlin.stdlib.default.dependency = false` because the IntelliJ Platform ships its
    // own Kotlin stdlib and a second copy inside a plugin ZIP causes classloader conflicts. This module
    // is not a plugin, so it has to declare the stdlib itself — but as `compileOnly`, so it is not
    // dragged into the plugin ZIP through `implementation(project(":core"))`. Runtime hosts provide it:
    // the plugins get it from the platform, the standalone app depends on it directly.
    compileOnly(kotlin("stdlib"))

    testImplementation(kotlin("stdlib"))
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}
