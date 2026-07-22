// Comparison views in Compose, shared by the standalone app and (once its UI migrates) the plugin.
//
// Compose is `compileOnly` on purpose. The two hosts obtain it differently: the standalone app ships
// its own runtime, while an IntelliJ plugin MUST take Compose, Skiko and Jewel from the platform -
// bundling a second copy puts the same classes on two classloaders inside one process and breaks.
// Declaring it `implementation` here would quietly drag Compose into the plugin ZIP.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

repositories {
    mavenCentral()
    google()
    maven("https://packages.jetbrains.team/maven/p/kpm/public/")
}

dependencies {
    compileOnly(project(":core"))
    compileOnly(kotlin("stdlib"))

    compileOnly(compose.runtime)
    compileOnly(compose.foundation)
    compileOnly(compose.ui)
    compileOnly(compose.desktop.currentOs)
}

kotlin {
    jvmToolchain(21)
}
