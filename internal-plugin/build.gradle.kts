import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("pluginGroup").get()
// Versioned independently from the public plugin; falls back to pluginVersion if unset.
version = providers.gradleProperty("figmaPluginVersion")
    .orElse(providers.gradleProperty("pluginVersion"))
    .get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
        intellijDependencies()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())

        // Depend on the public plugin: puts its classes on the compile/test classpath and composes it
        // into the runIde sandbox, so the Figma extension can implement the public ExtraComparisonSource
        // extension point. At install time this is enforced by <depends> in plugin.xml.
        localPlugin(project(":public-plugin"))

        // Bundled plugins whose types the referenced public-plugin classes may transitively touch.
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("Git4Idea")

        testFramework(TestFrameworkType.Platform)
    }

    // Plain JUnit 4 for the pure-logic unit tests (no IDE fixture needed).
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    instrumentCode = false
    buildSearchableOptions = false

    pluginConfiguration {
        changeNotes = """
              <ul>
              <li>On first run the golden and generated-output directories now default to the project's
              standard layout (<code>screenshotTests/src/test/screenshots</code> and
              <code>screenshotTests/build/outputs/roborazzi</code>), so the tool window works without
              manual setup.</li>
              </ul>
        """.trimIndent()

        ideaVersion {
            sinceBuild = "241"
            untilBuild = provider { null }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.named<Zip>("buildPlugin") {
    archiveBaseName.set("golden-diff-figma")
}

// Generates the `updatePlugins.xml` custom-repository descriptor next to the built ZIP. The ZIP and
// this descriptor are hosted directly from this public GitHub repo (committed under distribution/ and
// served via raw.githubusercontent.com — no releases, no auth). `distribution/publish.sh` copies the
// built files into that folder. Override -PcustomRepoBaseUrl only if the repo/branch/path changes.
tasks.register("generateUpdatePluginsXml") {
    group = "distribution"
    description = "Writes updatePlugins.xml for the custom plugin repository."
    val buildPlugin = tasks.named<Zip>("buildPlugin")
    dependsOn(buildPlugin)
    val pluginId = "com.github.dkwasniak.goldendiff.figma"
    val pluginName = "Golden Diff — Figma"
    val vendor = "dkwasniak"
    val dependsOnId = "com.github.dkwasniak.goldendiff"
    val pluginVersion = version.toString()
    val baseUrl = providers.gradleProperty("customRepoBaseUrl")
        .orElse("https://raw.githubusercontent.com/dkej123/goldendiff/main/distribution")
    val zipFile = buildPlugin.flatMap { it.archiveFile }
    val outputFile = layout.buildDirectory.file("distributions/updatePlugins.xml")
    inputs.property("version", pluginVersion)
    inputs.property("baseUrl", baseUrl)
    outputs.file(outputFile)
    doLast {
        val zipName = zipFile.get().asFile.name
        // <name>/<vendor>/<description> make the IDE show a proper listing before download; without
        // <name> it falls back to the ZIP file name (e.g. "golden-diff-figma-1.3.0").
        outputFile.get().asFile.writeText(
            """
            <plugins>
              <plugin id="$pluginId" url="${baseUrl.get().trimEnd('/')}/$zipName" version="$pluginVersion">
                <name>$pluginName</name>
                <vendor>$vendor</vendor>
                <idea-version since-build="241"/>
                <depends>$dependsOnId</depends>
                <description><![CDATA[Adds a Figma reference comparison source to the Golden Diff plugin.]]></description>
              </plugin>
            </plugins>
            """.trimIndent() + "\n",
        )
    }
}
