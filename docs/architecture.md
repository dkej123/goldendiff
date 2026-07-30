# Architecture

Kotlin. The comparison logic is a plain JVM library; on top of it sit two Swing-based IntelliJ
plugins and a standalone Compose desktop application. The hosts share logic through `:core`, but
intentionally keep separate UI layers. `:core-ui` contains Compose canvases for the app only.

## Modules

| Module | What it is | May depend on |
|---|---|---|
| `:core` | All tool-agnostic logic: golden matching, git access, pixel diff, merged change scanning, snapshot file index, bulk accept, notification policy, config. | JDK only — **never** IntelliJ, Swing or Compose |
| `:core-ui` | Compose comparison canvases for the desktop app. Compose is `compileOnly`. | `:core` |
| `:public-plugin` | Golden Diff — Swing tool window/settings, Kotlin PSI, VCS. | `:core` (`implementation`) |
| `:internal-plugin` | Golden Diff — Figma. | `:core` (`compileOnly`), `:public-plugin` |
| `:app` | Standalone desktop app. macOS only for now. | `:core`, `:core-ui` |

Why `:core` is plain `kotlin-jvm` and not Kotlin Multiplatform: every consumer is a JVM, so KMP would
only add `expect/actual` ceremony around `File`, `BufferedImage`, `ImageIO` and git without unlocking
a platform. It would also be unusable from the plugin side — the IntelliJ Platform Gradle Plugin looks
for `compileKotlin`/`jar` while KMP produces `compileKotlinJvm`/`jvmJar`, and
[silently builds a plugin ZIP with no Kotlin classes in it](https://github.com/JetBrains/intellij-platform-gradle-plugin/issues/1507).

The dependency directions on `:core` are asymmetric on purpose and both mistakes compile cleanly —
see [gotchas](gotchas.md#module-boundaries-two-traps-that-compile-fine-and-fail-at-runtime).

## What the two hosts do differently

Only two things differ at this layer:

- **Git status and HEAD content.** Both hosts construct the same
  `ChangeScanner.forGitProject(...)`, backed by `GitCli` for both `HeadBytesSource` and
  `WorkingCopyStatus`. This is intentional: IDE VCS mappings may fail to return binary revision
  content and must not make the plugin label tracked goldens as New while the app labels them
  Unchanged.
- **Which file drives matching.** The plugin reads the open editor (`CurrentScreen`, Kotlin PSI). The
  app has no editor, so the user picks a file from the project list and it goes through
  `GenericScreenExtractor`. Both produce a `Screen` and feed the same `GoldenFinder`.

## Two-plugin layout
The repo builds **two plugins** from two Gradle modules that share one root build:

- **`public-plugin/`** — Golden Diff (`com.github.dkwasniak.goldendiff`). Everything described in this
  doc lives here. Published to Marketplace.
- **`internal-plugin/`** — Golden Diff — Figma (`com.github.dkwasniak.goldendiff.figma`). Only the
  Figma comparison source (`compare/Figma*`). Declares `<depends>com.github.dkwasniak.goldendiff</depends>`,
  so at runtime its classloader's parent is the public plugin's and it sees the public plugin's public
  API. Built with `localPlugin(project(":public-plugin"))`. Distributed through a custom plugin
  repository (see [build-and-run.md](build-and-run.md#distributing-the-internal-plugin)); not Marketplace.

The seam between them is one **extension point**: the public plugin declares
`<extensionPoint name="comparisonSource" interface="…variant.ExtraComparisonSource" dynamic="true"/>`
and consumes the contributions via `ExtraComparisonSources.all` (`ExtensionPointName.extensionList`).
The internal plugin registers `FigmaImageSource` against that point. `ExtraComparisonSource` is the
only public API the internal plugin depends on, plus the incidental public classes `GitImageSource`,
`ScreenshotSettings`, and `CurrentScreen.Screen`. First-run directory defaults for a fresh project are
contributed the same way, through `ExtraComparisonSource.firstRunDefaults()`.

Adding another team-internal feature = another dependent plugin (or another `comparisonSource`),
never a fork of the public plugin.

## Package layout
`public-plugin/src/main/kotlin/com/github/dkwasniak/goldendiff/` (Figma sources:
`internal-plugin/src/main/kotlin/…/compare/Figma*`)

- **`toolwindow/`** — UI entry point and the list side.
  - `ScreenshotToolWindowFactory` — Java `ToolWindowFactory` that installs
    `RedesignedScreenshotPanel`.
  - `RedesignedScreenshotPanel` — the Swing shell for Files → Goldens → Compare, responsive
    Wide/Medium/Narrow layouts, command and Accept actions, editor/VFS subscriptions and extension
    items in the shared Goldens grid.
  - `PluginSnapshotController` — typed lifecycle and stable View/Case/golden selection state. It
    rejects stale asynchronous refreshes and contains no Swing widget references.
  - `SnapshotFilesPanel` / `MergedGoldenCellRenderer` — native JBUI adapters over the shared snapshot
    projection and merged golden model.
  - `BatchCommandExecution` — sequential View command batches in one IntelliJ Run content, including
    partial-failure and cancellation outcomes.
- **`match/`** — figuring out what to show.
  - `CurrentScreen` — returns typed candidates for the selected editor: screenshot/test function
    names, class names, file base name, and `caretName`. **Kotlin** files are read via `KtFile` PSI;
    every other language falls back to `GenericScreenExtractor`. The candidate set is
    **caret-independent** (stable per file). `caretName` is separate (Kotlin only), used for the
    initial selection.
  - `GenericScreenExtractor` — language-neutral, text-based candidate extraction for non-Kotlin files
    (TS/JS/Swift/Java/…). Pure and unit-tested; touches no language-plugin PSI, so it adds no plugin
    dependencies and keeps current-file matching tool-agnostic.
  - `GoldenFinder` — scans configured dirs for `*.png`. `find(roots, screen, mode, …)` matches each
    golden's path **relative to its root** using one of two `MatchMode`s: `ANNOTATED_METHOD` (path
    contains an annotated function name) or `FILE_CLASS_REGEX` (user regex with `{file_name}`
    / `{class_name}`). Excludes `_compare` / `_actual` by default.
  - `MatchMode` / `MatchingDefaults` / `AnnotationNameMatcher` — matching modes, default rules, and
    pure matching helpers used by tests.
- **`compare/`** — the viewer.
  - `GitImageSource` remains a public IntelliJ VCS adapter for extension compatibility. The built-in
    plugin workflow uses the same `GitCli` HEAD reads as the standalone app; `decode()` turns bytes
    into `BufferedImage`. Call all Git/image work off the EDT.
  - `GeneratedImageSource` / the shared generated counterpart index resolve Test output. Matching
    prefers the same relative directory, reports ambiguity instead of choosing an arbitrary
    basename collision, and reuses one generated-root scan during a refresh.
  - `CompareView` hosts the Swing `TwoUpPanel`, `SwipePanel`, `OnionSkinPanel`, `DiffPanel` and
    `SingleImagePanel`, with shared zoom controls. Geometry, transparent borders and pixel-diff data
    come from `:core` (`ImageLayout`, `TransparentBorder`, `PixelDiff`).
  - `ImagePainting` adapts the core geometry for Swing painting and remains part of the public Figma seam.
- **`settings/`** — configuration.
  - `ScreenshotSettings` — project-level `PersistentStateComponent`, holds the list of golden dirs.
  - `ScreenshotConfigurable` — Settings → Tools → Golden Diff (edit the dir list).

## Data flow
The plugin and standalone app use the same snapshot-aware model:

1. `ProjectFileIndex` supplies source candidates; `SnapshotFileIndex` scans golden roots once, runs
   pure `GenericScreenExtractor` + `GoldenFinder.matchFiles` projections, and retains
   view → snapshot-case → golden associations. PNG-only refreshes reuse these associations and
   recompute statuses instead of reparsing every source.
2. `ChangeScanner.mergedItemsFor` / `mergedChanges` produce one `MergedGoldenItem` per golden with
   independent Working-copy and Test-output variants.
3. `SnapshotFileIndex.projectTree` supplies the host-neutral Folder → View → Snapshot case projection,
   search/filter results, stable ids and failing-View folder counts.
4. `PluginSnapshotController` retains selection across equivalent refreshes. Editor tab changes select
   the corresponding View; caret movement is not observed. Built-in and extension cards have
   source-qualified stable ids, while the last built-in golden selection is retained independently.
5. The Compare pane derives its built-in source on every state transition: actionable Test output,
   otherwise Working copy, otherwise the only available variant. Extension items such as Figma sit
   beside those built-in cards and load their own comparison only when selected; they never become a
   Working/Test source and cannot be accepted.
6. `GoldenAccept.acceptResolved` / `acceptAllResolved` validate the exact generated source identity
   displayed or frozen for confirmation and return complete typed outcomes.

Completion notification eligibility and copy live in the pure `CompletionNotificationPolicy`.
Delivery remains host-specific: macOS Notification Center in `:app`, IntelliJ notifications in
`:public-plugin`; no platform notification API crosses into `:core`.

## Threading
PSI reads via `ReadAction.compute`; scanning / git / image decode on
`AppExecutorUtil.getAppExecutorService()`; UI updates via `ApplicationManager.invokeLater`.

`CurrentScreen` and `ExtraComparisonSource.findItems()` also run on the background executor. Their
results carry a separate generation, so a slow extension response from a previous editor cannot
replace the active file's cards. Extension-point changes trigger the same lightweight refresh, which
keeps the public plugin unchanged when no dependent extension is installed.
