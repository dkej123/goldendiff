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
    }
}

intellijPlatform {
    // We have no Java/GUI-form sources, so code instrumentation is unnecessary.
    instrumentCode = false
    // No custom searchable settings to index — skip for faster builds.
    buildSearchableOptions = false

    pluginConfiguration {
        changeNotes = """
            <ul>
              <li>Initial public preview.</li>
              <li>Compare screenshot goldens from Git HEAD with working-copy or generated test-output images.</li>
              <li>Review changes in side-by-side, swipe, and onion-skin modes.</li>
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

tasks {
    wrapper {
        gradleVersion = "9.6.1"
    }
}
