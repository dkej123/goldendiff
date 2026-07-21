import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
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
              <li><b>Project changes scope.</b> A new Scope selector in the tool window header switches
              the list between the file open in the editor and every changed golden across the whole
              project. Only changed goldens are shown, sorted changed-first then new; works with both
              the working-copy and test-output comparison sources.</li>
              <li>Faster golden matching in file/class-regex mode: each pattern is compiled once per
              refresh instead of being recompiled for every candidate file.</li>
              <li>The Project changes working-copy view reads each screenshot's changed/new status
              directly from <code>git status</code>, avoiding a per-file HEAD revision read.</li>
              <li>The Project changes test-output view indexes the generated directory once for O(1)
              counterpart lookups instead of re-scanning it per golden, and computes the HEAD
              comparisons in parallel.</li>
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
    archiveBaseName.set("golden-diff")
}
