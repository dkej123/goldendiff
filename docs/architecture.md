# Architecture

Plain IntelliJ Platform plugin. Kotlin + Swing UI, with one tiny Java tool-window factory to keep
Plugin Verifier happy. No GUI forms.

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
  - `ScreenshotToolWindowFactory` — registers the right-anchored tool window (`plugin.xml`).
  - `ScreenshotPanel` — the whole tool window content: header (choose dirs / refresh / scope / compare
    source), the golden list (left) and the `CompareView` (right) in a `JBSplitter`. Owns the refresh
    logic and the editor listener. Implements `Disposable`. The **Scope** combo switches between
    current-file matching and a **Project changes** view: working-copy changes come from
    `git status --porcelain` (status derived directly from the porcelain code, no per-file HEAD read);
    test-output changes index the generated tree once and classify goldens with parallel HEAD reads.
  - `GoldenCellRenderer` — thumbnail + filename cell renderer with an icon cache.
- **`match/`** — figuring out what to show.
  - `CurrentScreen` — reads the selected editor's `KtFile` via PSI and returns typed candidates:
    screenshot/test function names, class names, file base name, and `caretName`. The candidate set is
    **caret-independent** (stable per file). `caretName` is separate, used only for initial selection.
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
  - `CompareView` — hosts the four cards + the mode toggle + the zoom combo; `showComparison()` vs
    `showSingle()`.
  - `TwoUpPanel`, `SwipePanel`, `OnionSkinPanel`, `SingleImagePanel` — the views. Each wraps an inner
    canvas (a `ZoomablePanel`) in a `JBScrollPane`.
  - `ZoomablePanel` — base canvas: zoom model + preferred-size logic (fit vs scaled-with-scrollbars).
  - `ImagePainting` — shared helpers: `fitRect`, `renderRect(zoom,…)`, checkerboard, hi-quality draw.
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
3. List selection → `loadComparison(file)` on a pooled thread: HEAD bytes vs the selected source.
   Source defaults to the working-copy golden, or can be switched to test-generated output.
   - Bytes equal → `CompareView.showSingle(…, "No changes vs HEAD")`.
   - Otherwise → `CompareView.showComparison(head, working, …)` with the three modes.

## Threading
PSI reads via `ReadAction.compute`; scanning / git / image decode on
`AppExecutorUtil.getAppExecutorService()`; UI updates via `ApplicationManager.invokeLater`.
