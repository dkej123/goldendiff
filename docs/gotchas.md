# Gotchas (hard-won)

Read this before changing the build or the refresh logic.

## Platform compatibility
- The plugin is compiled against **IntelliJ Platform 2024.1 / build 241** and declares compatibility
  through **254.\***. Keep code on stable APIs available in 241 if you want the broad range to stay true.
- Do not re-add Android Studio specific APIs. The plugin should remain installable in IntelliJ IDEA and
  Android Studio as long as Kotlin and Git4Idea bundled plugins are present.
- On build 253+, `ideaIC` no longer exists; if you retarget back to 2025.3, use `intellijIdea("2025.3")`.
- **Gradle 9+ required** by IntelliJ Platform Gradle Plugin 2.17.0. Wrapper is pinned to 9.6.1.

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

## Stable-APIs-only rule
Compiled against 241, and we deliberately use only long-stable platform APIs
(`ToolWindowFactory`, `DiffProvider`, `ByteBackedContentRevision`, `FileChooserDescriptorFactory`,
`ToolbarDecorator`, PSI) so the plugin also loads in Android Studio without AS-specific dependencies.
