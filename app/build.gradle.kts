import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Golden Diff as a standalone desktop application.
//
// Plain kotlin-jvm rather than Kotlin Multiplatform: every target is a JVM, so KMP would only add
// expect/actual ceremony around File, BufferedImage, ImageIO and git without buying a new platform.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    google()
    maven("https://packages.jetbrains.team/maven/p/kpm/public/")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":core-ui"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material)
    // Unlike the plugin - which must take Compose from the IDE - the app ships its own runtime, so
    // the Kotlin stdlib has to be a real dependency here (the root disables it by default for the
    // plugin modules).
    implementation(kotlin("stdlib"))

    testImplementation(kotlin("stdlib"))
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}

compose.desktop {
    application {
        mainClass = "com.github.dkwasniak.goldendiff.app.MainKt"

        // Packaging needs a JDK that ships jpackage. A JetBrains Runtime does not - and since this
        // repo is usually built with Android Studio's JBR, `packageDmg` fails there with a bare
        // "'jpackage' is missing" unless pointed elsewhere. Running and testing the app are
        // unaffected; only the installer tasks care.
        //
        // Resolution order: -PappJavaHome, then JAVA_HOME, then whatever Gradle is running on.
        providers.gradleProperty("appJavaHome")
            .orElse(providers.environmentVariable("JAVA_HOME"))
            .orNull
            ?.let { javaHome = it }

        nativeDistributions {
            // All three are declared even though only macOS is built and verified for now: jpackage
            // ignores formats foreign to the host it runs on, so adding a CI runner later needs no
            // change here.
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Golden Diff"
            packageVersion = providers.gradleProperty("pluginVersion").get()
        }
    }
}
