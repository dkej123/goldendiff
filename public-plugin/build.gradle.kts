import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

val distributionSuffix = providers.gradleProperty("distributionSuffix")
val marketplaceChannel = providers.gradleProperty("marketplaceChannel")

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
    implementation(project(":telemetry"))

    intellijPlatform {
        // Build against the oldest supported 2024.x Community platform.
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

val generatedTelemetryResources = layout.buildDirectory.dir("generated/telemetry-resources")
val telemetryVersion = version.toString()
val generateTelemetryResources = tasks.register<WriteProperties>("generateTelemetryResources") {
    // Local (and ordinary CI) builds are developer builds; only the publish workflow passes
    // -PreleaseBuild=true. A developer build is forced fully offline here: empty Amplitude/Sentry
    // keys regardless of gradle.properties or the CLI, so no event can reach a backend.
    val developerBuild = !providers.gradleProperty("releaseBuild").map { it.toBoolean() }.getOrElse(false)
    val dsn = if (developerBuild) "" else providers.gradleProperty("sentryPluginDsn").getOrElse("")
    val amplitudeApiKey = if (developerBuild) "" else providers.gradleProperty("amplitudeApiKey").getOrElse("")
    destinationFile = generatedTelemetryResources.map { it.file("golden-diff-telemetry.properties") }.get().asFile
    property("sentry.dsn", dsn)
    property("amplitude.api_key", amplitudeApiKey)
    property("version", telemetryVersion)
    property("build.developer", developerBuild.toString())
}

sourceSets.main {
    resources.srcDir(generatedTelemetryResources)
}
tasks.named("processResources") {
    dependsOn(generateTelemetryResources)
}

intellijPlatform {
    // We have no GUI-form sources, so code instrumentation is unnecessary.
    instrumentCode = false
    // No custom searchable settings to index — skip for faster builds.
    buildSearchableOptions = false

    pluginConfiguration {
        changeNotes = """
              <ul>
              <li>Adds optional anonymous product analytics with a dedicated consent prompt and
              independent controls for analytics and crash reporting in Settings.</li>
              <li>Analytics is processed by Amplitude in its European data region; diagnostic
              exceptions and privacy-safe performance spans remain in Sentry EU.</li>
              <li>Telemetry never includes filenames, project paths, source code, image content or
              identifiers from optional comparison-source extensions.</li>
              <li>Fixes alignment and wrapping in the plugin's telemetry dialog and Privacy settings,
              and shows the exact installed plugin version.</li>
              </ul>
        """.trimIndent()

        ideaVersion {
            sinceBuild = "241"
            // Swing plus stable platform, Kotlin PSI and Git4Idea APIs allow an open-ended range.
            untilBuild = provider { null }
        }
    }

    publishing {
        token = providers.gradleProperty("intellijPlatformPublishingToken")
        if (marketplaceChannel.isPresent && marketplaceChannel.get().isNotBlank()) {
            channels = listOf(marketplaceChannel.get())
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// Build with JDK 21 but emit Java 17 bytecode for IntelliJ Platform 241–243 hosts.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.named<Zip>("buildPlugin") {
    archiveBaseName.set("golden-diff")
    // A developer build (the default) gets a `-dev` suffix so it never masquerades as a release
    // artifact; an explicit distributionSuffix still wins, and -PreleaseBuild=true keeps the plain
    // `golden-diff-<ver>.zip` name the Marketplace publication expects.
    val releaseBuild = providers.gradleProperty("releaseBuild").map { it.toBoolean() }.getOrElse(false)
    val suffix = when {
        distributionSuffix.isPresent -> distributionSuffix.get()
        !releaseBuild -> "dev"
        else -> null
    }
    if (suffix != null) {
        archiveVersion.set("${project.version}-$suffix")
    }
}
