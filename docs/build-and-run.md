# Build & run

## Toolchain (all pinned)
- JDK **21** (Android Studio's JBR works: `/Applications/Android Studio.app/Contents/jbr/Contents/Home`).
- Gradle **9.6.1** (wrapper). Required because IntelliJ Platform Gradle Plugin 2.17.0 needs Gradle 9+.
- Kotlin JVM plugin **2.2.20**.
- IntelliJ Platform Gradle Plugin **2.17.0**.
- Platform dependency: **`intellijIdeaCommunity("2024.1")`**.

Key `build.gradle.kts` bits: `instrumentCode = false` and `buildSearchableOptions = false` (no Java/
form sources, no custom searchable settings), `ideaVersion { sinceBuild = "241"; untilBuild = "254.*" }`.

## Commands
```bash
./gradlew buildPlugin      # assembles build/distributions/screenshot-compare-<ver>.zip
./gradlew verifyPlugin     # plugin structure verifier
./gradlew runIde           # launches a sandbox IntelliJ IDEA (NOT Android Studio)
```

First build downloads the platform — slow, run it in the background and
be patient. Subsequent builds are seconds (platform is cached).

## Installing into Android Studio / IntelliJ
The dev target is IntelliJ IDEA (only stable platform + Kotlin-PSI + Git4Idea APIs are used, all present
in Android Studio builds based on supported IntelliJ Platform versions). To install the built plugin:
1. Settings → Plugins → ⚙ → **Install Plugin from Disk…** → the zip in `build/distributions/`.
2. Restart Android Studio.
Changing `<id>` counts as a new plugin, so an old-id build must be uninstalled first.

## Running the sandbox inside real Android Studio (optional)
Swap `intellijIdeaCommunity("2024.1")` for `androidStudio("<version>")` in `build.gradle.kts`. Heavier download
and the AS version must be resolvable by the Gradle plugin. Not needed for normal development.
