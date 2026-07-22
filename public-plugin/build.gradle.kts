import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
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
    // Must be `implementation`, not `compileOnly`: :core has to be packaged INTO the plugin ZIP.
    // The Figma plugin resolves core classes through this plugin's classloader (it is its parent), so
    // leaving them out compiles fine — they are on the compile classpath via localPlugin(...) — and
    // only fails at runtime with NoClassDefFoundError. `unzip -l` the ZIP after touching this.
    implementation(project(":core"))
    implementation(project(":core-ui"))

    intellijPlatform {
        // Build against the oldest supported platform. From 2025.3 onward Community and Ultimate are
        // published as a unified `intellijIdea(...)` artifact, but 2025.1 still has the smaller
        // Community distribution.
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())

        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("Git4Idea")

        // Compose, Skiko and Jewel are supplied by the target IDE. Never replace these with Maven
        // implementation dependencies: a second runtime in the plugin ZIP breaks classloading.
        bundledModule("intellij.platform.jewel.foundation")
        bundledModule("intellij.platform.jewel.ui")
        bundledModule("intellij.platform.jewel.ideLafBridge")
        bundledModule("intellij.libraries.compose.foundation.desktop")
        bundledModule("intellij.libraries.skiko")

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
            // 251 = 2025.1, the first platform that bundles Jewel and the Compose modules the shared
            // UI needs. See docs/gotchas.md.
            sinceBuild = "251"
            // Still no upper bound: at this point the plugin remains pure Swing and uses only
            // long-stable platform + Kotlin-PSI + Git4Idea APIs. This has to become a bounded range
            // once the tool window moves to Compose/Jewel, because those are versioned per platform
            // build and carry no binary-compatibility guarantee. Note that pinning untilBuild to a
            // branch that does not exist yet is rejected by the Marketplace verifier.
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

// Bytecode 21 is fine from sinceBuild = 251 on: IntelliJ 2025.1+ runs on JBR 21. The previous
// per-task JVM_17 override existed only because 2024.1–2024.3 ran on JBR 17 and would have failed
// with UnsupportedClassVersionError; with 241–243 dropped it is no longer needed.

tasks.named<Zip>("buildPlugin") {
    archiveBaseName.set("golden-diff")
}
