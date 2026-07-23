// Comparison views in Compose, used exclusively by the standalone desktop app.
//
// Compose is `compileOnly` on purpose: the app supplies the runtime. Keeping the runtime out of this
// library also makes the module boundary explicit and prevents accidental dependency leakage.
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
