# Architecture

Kotlin. The comparison logic is a plain JVM library; on top of it sit two Swing-based IntelliJ
plugins and a standalone Compose desktop application. The hosts share logic through `:core`, but
intentionally keep separate UI layers. `:core-ui` contains Compose canvases for the app only.

## Modules

| Module | What it is | May depend on |
|---|---|---|
| `:core` | All tool-agnostic logic: golden matching, git access, pixel diff, change scanning, project file index, config. | JDK only — **never** IntelliJ, Swing or Compose |
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

Only three things, all behind interfaces in `:core`:

- **HEAD content.** The plugin uses the IDE's VCS layer (`GitImageSource`), which knows the repo,
  honours the user's git config and caches revisions. The app uses `GitCli`. Both are `HeadBytesSource`.
- **Project-wide status.** Both use `GitCli.changedFiles()` — one git call beats a per-file revision
  fetch, so even the plugin shells out here. (`WorkingCopyStatus`.)
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
  - `ScreenshotToolWindowFactory` — Java `ToolWindowFactory` that installs `ScreenshotPanel`.
  - `ScreenshotPanel` — the complete Swing tool window: header, wrapping thumbnail list, draggable
    split and comparison view. It owns refresh state and the editor listener and implements
    `Disposable`. The **Scope** control switches between
    current-file matching and a **Project changes** view: working-copy changes come from
    `git status --porcelain` (status derived directly from the porcelain code, no per-file HEAD read);
    test-output changes index the generated tree once and classify goldens with parallel HEAD reads.
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
    contains an annotated/`test*` function name) or `FILE_CLASS_REGEX` (user regex with `{file_name}`
    / `{class_name}`). Excludes `_compare` / `_actual` by default.
  - `MatchMode` / `MatchingDefaults` / `AnnotationNameMatcher` — matching modes, default rules, and
    pure matching helpers used by tests.
- **`compare/`** — the viewer.
  - `GitImageSource` — loads HEAD bytes via VCS `DiffProvider` + `ByteBackedContentRevision`, and
    working-copy bytes from disk; `decode()` turns bytes into `BufferedImage`. Call off the EDT.
  - `GeneratedImageSource` — resolves the test-output counterpart for a selected golden. It filters
    generated files with the configured regex, uses the first capture group as the golden basename,
    prefers the same relative directory under generated-output roots, and falls back to a full scan.
  - `CompareView` hosts the Swing `TwoUpPanel`, `SwipePanel`, `OnionSkinPanel`, `DiffPanel` and
    `SingleImagePanel`, with shared zoom controls. Geometry, transparent borders and pixel-diff data
    come from `:core` (`ImageLayout`, `TransparentBorder`, `PixelDiff`).
  - `ImagePainting` adapts the core geometry for Swing painting and remains part of the public Figma seam.
- **`settings/`** — configuration.
  - `ScreenshotSettings` — project-level `PersistentStateComponent`, holds the list of golden dirs.
  - `ScreenshotConfigurable` — Settings → Tools → Golden Diff (edit the dir list).

## Data flow
1. Editor selection changes → `ScreenshotPanel.scheduleRefresh()` (debounced ~300 ms).
2. `refresh()` → in **Current file** scope: `CurrentScreen.compute()` (read action, using the
   configured annotation regex) → `GoldenFinder.find()` (using configured golden filename patterns) on
   a pooled thread → `populate()` on the EDT fills the list and picks an initial selection. In
   **Project changes** scope it instead builds the changed-golden list from `git status` (working copy)
   or the indexed generated tree (test output) on a pooled thread.
3. Grid selection → `loadComparison(file)` on a pooled thread: HEAD bytes vs the selected source.
   Source defaults to the working-copy golden, or can be switched to test-generated output.
   - Bytes equal → the Swing single-image panel with "No changes vs HEAD".
   - Otherwise → the Swing comparison view with all four modes.

## Threading
PSI reads via `ReadAction.compute`; scanning / git / image decode on
`AppExecutorUtil.getAppExecutorService()`; UI updates via `ApplicationManager.invokeLater`.
