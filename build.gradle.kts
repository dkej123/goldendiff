import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.17.0"
}

group = providers.gradleProperty("pluginGroup").get()
val pluginBaseVersion = providers.gradleProperty("pluginVersion").get()

val goldenDiffVariant = providers.gradleProperty("goldenDiffVariant")
    .orElse("public")
    .map { it.lowercase() }
    .get()

require(goldenDiffVariant == "public" || goldenDiffVariant == "figma") {
    "Unsupported goldenDiffVariant '$goldenDiffVariant'. Use 'public' or 'figma'."
}

version = if (goldenDiffVariant == "figma") "$pluginBaseVersion-figma" else pluginBaseVersion

layout.buildDirectory.set(layout.projectDirectory.dir("build/$goldenDiffVariant"))

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
              <li>Screenshot thumbnails are now sorted by status, with changed screenshots first and
              new screenshots next. The list also shows a compact legend and subtle red/green status
              accents.</li>
              <li>Added thumbnail scaling controls so the screenshot list can switch from a large
              single-column view to a denser grid.</li>
              <li>Comparison labels are now clipped gracefully in narrow tool windows instead of
              overlapping.</li>
              <li>Pixel diff now downscales mismatched image sizes before comparison, so pure size
              differences no longer dominate the heatmap.</li>
            </ul>
        """.trimIndent()

        ideaVersion {
            sinceBuild = "241"
            // No upper bound. The plugin uses only long-stable platform + Kotlin-PSI + Git4Idea APIs
            // (see docs/gotchas.md "Stable-APIs-only rule"), so it should load in current and future
            // IDE builds without a re-release. Pinning untilBuild to a concrete future branch is also
            // rejected by the Marketplace verifier while that version does not exist yet (e.g. "254.*"
            // → "Version '2025.4' does not exist").
            untilBuild = provider { null }
        }
    }

    publishing {
        token = providers.gradleProperty("intellijPlatformPublishingToken")
    }
}

kotlin {
    jvmToolchain(21)

    sourceSets {
        named("main") {
            if (goldenDiffVariant == "figma") {
                kotlin.srcDir("src/figma/kotlin")
            }
        }
        named("test") {
            if (goldenDiffVariant == "figma") {
                kotlin.srcDir("src/figmaTest/kotlin")
            }
        }
    }
}

sourceSets {
    named("main") {
        if (goldenDiffVariant == "figma") {
            resources.srcDir("src/figma/resources")
        }
    }
    named("test") {
        if (goldenDiffVariant == "figma") {
            resources.srcDir("src/figmaTest/resources")
        }
    }
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

tasks.named<Zip>("buildPlugin") {
    archiveBaseName.set(if (goldenDiffVariant == "figma") "golden-diff-figma" else "golden-diff")
    destinationDirectory.set(layout.projectDirectory.dir("build/distributions"))
}

tasks {
    wrapper {
        gradleVersion = "9.6.1"
    }

    register<Exec>("buildPublicPlugin") {
        group = "build"
        description = "Builds the public Marketplace plugin without Figma variant sources."
        commandLine("./gradlew", "buildPlugin", "-PgoldenDiffVariant=public")
    }

    register<Exec>("buildFigmaPlugin") {
        group = "build"
        description = "Builds the Figma plugin variant with Figma comparison sources."
        commandLine("./gradlew", "buildPlugin", "-PgoldenDiffVariant=figma")
    }
}
