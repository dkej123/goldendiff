# Architecture

Plain IntelliJ Platform plugin. Kotlin + Swing UI, with one tiny Java tool-window factory to keep
Plugin Verifier happy. No GUI forms.

## Package layout
`src/main/kotlin/com/github/dkwasniak/goldendiff/`

- **`toolwindow/`** — UI entry point and the list side.
  - `ScreenshotToolWindowFactory` — registers the right-anchored tool window (`plugin.xml`).
  - `ScreenshotPanel` — the whole tool window content: header (choose dirs / refresh / compare source),
    the golden list (left) and the `CompareView` (right) in a `JBSplitter`. Owns the refresh logic
    and the editor listener. Implements `Disposable`.
  - `GoldenCellRenderer` — thumbnail + filename cell renderer with an icon cache.
- **`match/`** — figuring out what to show.
  - `CurrentScreen` — reads the selected editor's `KtFile` via PSI and returns `Screen(names,
    caretName)`. `names` = class names + preview/test function names + file base name; it is
    **caret-independent** (stable per file). `caretName` is separate, used only for initial selection.
  - `GoldenFinder` — scans configured dirs for `*.png`. `find(roots, screen)` matches file names
    against `screen.names` (excludes `_compare` / `_actual`).
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
2. `refresh()` → `CurrentScreen.compute()` (read action) → `GoldenFinder.find()` on a pooled thread →
   `populate()` on the EDT fills the list and picks an initial selection.
3. List selection → `loadComparison(file)` on a pooled thread: HEAD bytes vs the selected source.
   Source defaults to the working-copy golden, or can be switched to test-generated output.
   - Bytes equal → `CompareView.showSingle(…, "No changes vs HEAD")`.
   - Otherwise → `CompareView.showComparison(head, working, …)` with the three modes.

## Threading
PSI reads via `ReadAction.compute`; scanning / git / image decode on
`AppExecutorUtil.getAppExecutorService()`; UI updates via `ApplicationManager.invokeLater`.
