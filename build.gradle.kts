import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.17.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
        intellijDependencies()
    }
}

dependencies {
    intellijPlatform {
        // Build against the oldest supported 2024.x Community platform. From 2025.3 onward Community
        // and Ultimate are published as a unified `intellijIdea(...)` artifact, but 2024.1 still has
        // the smaller Community distribution.
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())

        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("Git4Idea")

        testFramework(TestFrameworkType.Platform)

        // Pin the Plugin Verifier CLI to the version JetBrains Marketplace ran, so the `Verify Plugin`
        // workflow reproduces the same deprecated/internal/experimental API report. Bump as Marketplace
        // moves forward (latest is higher); see docs/gotchas.md.
        pluginVerifier("1.405")
    }

    // Plain JUnit 4 for the pure-logic unit tests (no IDE fixture needed).
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    // We have no GUI-form sources, so code instrumentation is unnecessary.
    instrumentCode = false
    // No custom searchable settings to index — skip for faster builds.
    buildSearchableOptions = false

    pluginConfiguration {
        changeNotes = """
            <ul>
              <li>Golden list thumbnails now render from the full-resolution image at paint time
              instead of a cached pre-scaled bitmap, so they stay crisp on HiDPI/Retina displays and
              use less memory.</li>
            </ul>
        """.trimIndent()

        ideaVersion {
            sinceBuild = "241"
            untilBuild = "254.*"
        }
    }

    publishing {
        token = providers.gradleProperty("intellijPlatformPublishingToken")
    }
}

kotlin {
    jvmToolchain(21)
}

// Build with JDK 21, but emit Java 17 bytecode: IntelliJ 2024.1–2024.3 (our sinceBuild = 241) run on
// JBR 17, so bytecode 21 would fail to load there with UnsupportedClassVersionError. Set per-task so it
// wins over the target the toolchain otherwise derives.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks {
    wrapper {
        gradleVersion = "9.6.1"
    }
}
