# Gotchas (hard-won)

Read this before changing the build or the refresh logic.

## Platform compatibility
- Both plugins compile against **IntelliJ Platform 2024.1 / build 241**, declare `sinceBuild = "241"`,
  and have no `untilBuild`. The plugin UI is Swing and uses long-stable platform APIs, Kotlin PSI and
  Git4Idea, so it is not tied to a platform-specific Compose/Jewel binary set.
- Do not re-add Android Studio specific APIs. The plugin should remain installable in IntelliJ IDEA and
  Android Studio as long as Kotlin and Git4Idea bundled plugins are present.
- On build 253+, `ideaIC` no longer exists; if you retarget back to 2025.3, use `intellijIdea("2025.3")`.
- **Gradle 9+ required** by IntelliJ Platform Gradle Plugin 2.17.0. Wrapper is pinned to 9.6.1.
- Builds use a JDK 21 toolchain. Plugin classes and the `core.jar` packaged in the public ZIP emit
  **Java 17 bytecode** for 241–243 hosts; the standalone app and `:core-ui` emit Java 21 bytecode.

## K2 mode
Recent Android Studio versions run Kotlin in **K2 mode** by default. A plugin using Kotlin PSI must declare
support or it is flagged incompatible. `plugin.xml` has:
```xml
<extensions defaultExtensionNs="org.jetbrains.kotlin">
    <supportsKotlinPluginMode supportsK2="true" />
</extensions>
```
This is valid because we only traverse PSI — no Kotlin **Analysis API** / resolve. If you ever add
resolve/analysis calls, revisit this.

## Build-output trap
Piping Gradle to `| tail` (or any pipe) makes the shell/background exit code reflect the **last pipe
stage**, not Gradle. A failed build can look like "exit code 0". **Always read the log** for
`BUILD SUCCESSFUL` / `BUILD FAILED`.

## First build is huge & slow
The IntelliJ IDEA download can crawl. Run builds in the background and wait; don't assume a stall.
After the first download the platform is cached and builds take seconds.

## runIde ≠ Android Studio
`runIde` launches a sandbox **IntelliJ IDEA**, not Android Studio. Fine for smoke-testing the plugin.
To run inside real AS, switch the platform dependency to `androidStudio(...)`.

## Refresh / caret (design constraint — don't regress)
Earlier bug: clicking in the editor rebuilt the list and reset the selection to the first item. Causes
and current design:
- The match `names` set must stay **caret-independent** (`CurrentScreen`). If `names` depended on the
  caret function, every click changed `names` → full rebuild → selection reset.
- There is **no caret listener**; refresh is driven by file-selection changes only. The list rebuilds
  only when the name set changes; manual list selection is preserved. Keep it this way.

## Module boundaries: two traps that compile fine and fail at runtime

**`:core` must be `implementation` in `:public-plugin`, `compileOnly` in `:internal-plugin`.** The
Figma plugin resolves core classes through the public plugin's classloader (its parent), so core has
to be *inside* `golden-diff-*.zip` and *absent* from `golden-diff-figma-*.zip`. Both mistakes compile:
`localPlugin(project(":public-plugin"))` puts everything on the compile classpath either way. Getting
it wrong surfaces only as `NoClassDefFoundError` when a user loads the Figma plugin. Verify with
`unzip -l public-plugin/build/distributions/golden-diff-*.zip`.

**`:core-ui` is app-only and Compose stays `compileOnly` there.** The app supplies its runtime; neither
plugin depends on `:core-ui`. If Compose, Jewel or Skiko appears in a plugin ZIP, a module boundary
has regressed; check with `unzip -l … | grep -iE 'compose|jewel|skiko'`.

**`kotlin.stdlib.default.dependency = false`** in `gradle.properties` means non-plugin modules get no
stdlib automatically. `:core` declares it `compileOnly` for the same reason — a second stdlib inside a
plugin ZIP conflicts with the platform's.

## `git status --porcelain` collapses untracked directories
Without `--untracked-files=all`, git reports a brand-new directory as one entry for the directory
itself rather than the files inside it. Callers filter on a `.png` extension, so those entries vanish
and a newly added golden folder shows as *no changes at all*. Fixed in `GitCli.changedFiles()`; do not
drop the flag. `-z` is equally load-bearing: it stops git quoting and escaping paths with spaces or
non-ASCII characters.

## macOS is case-insensitive — do not test ordering through the filesystem
`Alpha.kt` and `alpha.kt` are the same file on a default macOS volume, so a test that writes both and
asserts their order silently asserts nothing, then fails on Linux CI. `ProjectFileIndex.PATH_ORDER` is
exposed precisely so ordering can be tested against the comparator instead. `:core:test` runs on
`ubuntu-latest` in `package-app.yml` as the cheap early signal for this whole class of bug.

## `jpackage` is missing from a JetBrains Runtime
`:app:packageDmg` needs a full JDK. Building with Android Studio's JBR — the usual setup here — fails
with a bare `'jpackage' is missing`. Pass `-PappJavaHome=<full JDK 21+>` or set `JAVA_HOME`. Also note
jpackage cannot cross-compile: each platform's installer must be built on that platform.

## Stable-APIs-only rule
The open-ended compatibility range depends on keeping the plugin UI in Swing and using platform APIs
available in 241 (`ToolWindowFactory`, `DiffProvider`, `ByteBackedContentRevision`,
`FileChooserDescriptorFactory`, PSI). Do not introduce platform-bundled Compose/Jewel modules or
branch-specific APIs without revisiting the compatibility promise for both plugins.

## Plugin Verifier warnings (accepted, non-blocking)
The bundled verifier (IntelliJ Platform Gradle Plugin 2.17.0) reports **no** API-usage problems in our
code. The stricter Marketplace verifier (1.405) flags a small, constant set across all IDE versions —
all reviewed and **intentionally accepted**:
- **Internal API — Kotlin PSI** (`KtFile`, `KtClass`, `KtNamedFunction` and their members in
  `match/CurrentScreen.kt`). This is the sanctioned, unavoidable way to traverse Kotlin PSI; the K2
  note above applies. Do not chase these away.
- **Experimental API — `ByteBackedContentRevision`** (`compare/GitImageSource.kt`). Cleanest path to
  raw committed bytes; a non-experimental fallback (`ContentRevision.getContent()`) is already in place.
- **Deprecated API — none.** Two were replaced: the old `com.intellij.util.Alarm` debounce by a plain
  `javax.swing.Timer` (EDT, `restart()` = cancel + reschedule) in `toolwindow/ScreenshotPanel.kt`; and
  `ReadAction.compute(ThrowableComputable)` (deprecated on build 261+) by the Kotlin
  `com.intellij.openapi.application.runReadAction { }` in `match/CurrentScreen.kt`.

The verifier CLI is pinned to `1.405` in `build.gradle.kts` (`intellijPlatform { pluginVerifier(...) }`)
so the `Verify Plugin` workflow reproduces the same report Marketplace produced. Bump it as Marketplace
moves to newer verifier versions.
