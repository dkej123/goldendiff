// Root aggregator for the two-plugin layout:
//   :public-plugin   — Golden Diff, published to JetBrains Marketplace.
//   :internal-plugin — Golden Diff — Figma, a dependent plugin distributed through a custom
//                      plugin repository. It contributes the Figma comparison source to the public
//                      plugin via an extension point and is not published to Marketplace.
// Plugin versions are declared here once (apply false) and applied without a version in each module.
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.20" apply false
    id("org.jetbrains.intellij.platform") version "2.17.0" apply false
    // Compose Multiplatform backs the standalone desktop app. The compiler plugin is versioned with
    // Kotlin itself, so it tracks the Kotlin version above rather than the Compose one.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("org.jetbrains.compose") version "1.8.2" apply false
}

tasks.wrapper {
    gradleVersion = "9.6.1"
}
