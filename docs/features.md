# Features & behavior

## Tool window
Right-anchored, id "Golden Diff". Left: header + golden list. Right: the comparison viewer.

## Scope (current file ↔ whole project)
A **Scope** control in the header chooses what the list contains:
- **Current file** (default) — goldens matched against the Kotlin file open in the editor (see below).
- **Project changes** — every changed golden in the configured directories, regardless of the open file.
  Only changed items are listed (unchanged ones are dropped), sorted MODIFIED first, then NEW, then by
  path.
  - **Working copy** source: the changed set and its per-item status come straight from
    `git status --porcelain=v1 -z` — untracked/added/renamed/copied paths are **New**, everything else
    that differs is **Changed**. No per-file HEAD read is needed to classify them.
  - **Test output** source: goldens are matched to generated files by base name (the generated tree is
    indexed once), and each golden's status is computed by comparing its HEAD bytes with the generated
    output; these HEAD reads run in parallel.
  Variant-provided (extra) comparison sources are not available in this scope; selecting one falls back
  to Working copy.

### Standalone snapshot navigation
The standalone Files pane indexes only views that current matching rules associate with at least one
existing golden. It projects **Folder → View file → Snapshot case**. A case is an annotated preview,
test method, or supported story/function whose name matches one or more goldens. View rows start
collapsed; goldens without function-level evidence remain on the view row.

Search covers the view path, generator path, and case name and reveals matching descendants.
The **Failed**, **Modified**, and **New** status filters are combined with OR; with no status selected
the list shows everything, while selecting all three still excludes unchanged views and cases.
Labeled statuses are sorted above unchanged entries. A failed unmatched golden remains visible on its
view. A view is Failed when any associated Test output is new or differs from HEAD, while Working-copy
changes remain Modified or New. Folder counters count failed views rather than methods.

Selecting a source file loads its goldens. Selecting a golden loads its comparison without opening a
new tab; it also highlights the owning source file in the Files pane, expands the grouped tree when
needed, and scrolls that row into view.

## Matching goldens to the current file
- Refresh is triggered by **file selection changes only** (not caret moves), debounced ~300 ms.
- `CurrentScreen` builds caret-independent names. For **Kotlin** files (PSI): declared class names +
  the file base name + functions annotated with annotations matched by the configured annotation regex
  (default `.*Preview.*|Test`, excluding `PreviewParameter`). Unannotated functions, including names
  beginning with `test`, are not candidates in this mode. Plain `@Composable` helpers are intentionally
  ignored because small names such as `Stat` or `Content` create noisy false positives.
- For **any other file** (TypeScript, JavaScript, Swift, Java, plain text…) `GenericScreenExtractor`
  produces the same `Screen` from a language-neutral, text-based scan: the file base name + declared
  `class`/`struct`/`interface`/`enum` names + `function`/`func`/`fun` names + assigned React/arrow/
  styled components + single-token `describe`/`test`/`it`/`story` titles. This keeps current-file
  matching tool-agnostic (works for web screenshot tests: Playwright, Storybook, jest-image-snapshot…)
  without adding any language-plugin dependency. Matching in either case is done entirely by
  `GoldenFinder`, which is pure string matching over golden paths and language-agnostic.
- `GoldenFinder.find` lists PNGs in the configured dirs, using one of two mutually exclusive
  `MatchMode`s (chosen with a radio in Settings). Matching is done against each golden's path
  **relative to its root** (with `/` separators), so layouts that nest the class or package as
  directories still match.
  - **Match by annotated method** (default): a golden matches when its relative path *contains* the
    name of a function carrying a matching annotation as a whole word or camel-case segment.
  - **Match by file/class regex**: the user's regex patterns (one per line) are matched anywhere in
    the relative path. Patterns support `{file_name}` and `{class_name}` placeholders (values are
    regex-escaped before substitution; `{class_name}` expands to an alternation of the file's class
    names). Used when functions aren't annotated and matching keys off the file or class instead.
  - Files ending with a configured suffix are excluded first (default `_compare` / `_actual`; editable
    in settings, empty = exclude nothing). Results are sorted with the caret-function match first (in
    annotated-method mode), then by name.
- The list is rebuilt only when the name set actually changes. Clicking around the same file keeps the
  list and the user's manual selection intact. `caretName` is used only for the *initial* selection
  when a file is first shown.
- For working-copy and generated-output comparisons, screenshots that differ from git HEAD are sorted
  to the top of the list. Modified screenshots are marked with a subtle red accent; new screenshots
  (not present in HEAD) are marked with a subtle green accent. A small legend appears when such
  statuses are present.
- Thumbnail size can be adjusted from the tool window header. Smaller sizes turn the left pane into a
  wrapped grid so more screenshots can be scanned at once.
- See [the matching guide](matching.md) for end-user configuration examples.

## Comparison source (git HEAD ↔ working copy / test output)
For the selected golden, `GitImageSource` loads the committed version (VCS `DiffProvider` current
revision). In the plugin, the global "Compare" switch chooses the right side. In the standalone app,
Working copy and Test output are scanned together and one golden carries both variants; a local
Test/Working switch appears only when both exist, defaulting to Test output.

- **Working copy** — the selected golden file on disk.
- **Test output** — a generated screenshot from configured output directories. Matching first tries
  the same relative directory as the golden, then falls back to all files under the configured output
  roots. Generated files are filtered by a configurable regex. The default is
  `^(.+)_actual\.png$`, so `_actual` files are selected and `_compare` files are ignored. The first
  capture group is treated as the golden base name (`Foo_actual.png` → `Foo.png`).

The generated-file regex is not part of the current-file → golden-list matching. The list always comes
from golden filenames matched against names extracted from the current Kotlin file. The regex is used
only after the user selects a golden and chooses **Test output**, to find that golden's generated
counterpart.

- **Bytes equal** (no change vs HEAD) → single preview, status "No changes vs HEAD — <file>"; the mode
  toggle is hidden.
- **Differ** → the three modes are shown.
- New file (not in HEAD), missing working copy, missing generated output or missing generated-output
  configuration → single preview with an explanatory status.

## Four modes (+ zoom)
- **Side by side** — HEAD | selected comparison source, each fit into its half, labeled.
- **Swipe** — both drawn in the same rect; drag the vertical divider to reveal HEAD on the left and
  the selected comparison source on the right, with labels over the image.
- **Onion skin** — selected comparison source overlaid on HEAD; a dedicated control row above the
  canvas blends opacity without covering the screenshot, and the view labels the base/overlay roles.
- **Diff** — pixel heatmap (`PixelDiff` → `DiffPanel`): unchanged pixels dimmed to grayscale context,
  changed pixels highlighted in magenta (opacity scales with the per-pixel change), plus a
  "% pixels changed" readout. When both images exist but have different dimensions, both are
  downscaled to the smaller shared size before diffing so size-only differences do not dominate the
  heatmap. Areas present in only one image count as changed when there is no counterpart image.
- **Zoom** combo (always visible): `Fit / 50% / 75% / 100% / 150% / 200% / 400%`. Applies to all modes
  and the single view; zoomed-in content scrolls. Zoom level persists across files and modes.

## Running tests & accepting goldens
Beyond comparing, Golden Diff can **run the screenshot tests** and **accept generated output** — both
driven entirely by user configuration, with nothing framework-specific baked in.

### Configurable commands
Settings → Golden Diff → Commands holds five optional, free-form shell commands, stored per project:
- **Generate all goldens** — regenerate every golden for the project.
- **Generate for current file** — regenerate goldens for the file open in the editor (plugin) / selected
  in the app.
- **Verify all goldens** — run the whole verify/compare pass, producing test output.
- **Verify current file** — verify just the current file.
- **Verify current case** *(standalone app)* — verify one snapshot method/preview. When blank, the app
  falls back to Verify current file for that case's generator and labels the broader scope.

Any command left blank disables its corresponding button; hovering the disabled button shows a tooltip
explaining how to enable it (fill the command in Settings). The two **current file** commands may use
placeholders that are expanded from the active file before the command runs:
- `{file_name}` — the file's base name.
- `{class_name}` — the file's declared class name.
- `{file_path}` — the full path to the file.
- `{snapshot_file_name}` — the generator file's base name.
- `{snapshot_class_name}` — the generator class, falling back to its file name.
- `{snapshot_file_path}` — the project-relative generator path without its extension.
- `{snapshot_method_name}` — the test/preview function.

The legacy placeholders always describe the visible view parent. Snapshot placeholders describe the
generator, which may be a separate test file. Refreshing a view runs Verify current file once for each
unique generator and removes duplicate expanded commands. Batches run sequentially, continue after a
test failure, share one log, and can be cancelled.

Commands are user-authored shell strings; defaults are empty and nothing about Roborazzi, Gradle,
xcodebuild, iOS or Android is assumed (see [gotchas](gotchas.md) for how they are executed).

The standalone CLI's `configure --signal` hand-off is live: when it targets the project currently
open in the app, the app reloads the saved settings without reopening the project. Command-only
changes update the toolbar immediately; directory, matching, filtering or display changes also clear
the relevant caches and rescan. The onboarding walkthrough additionally advances past the setup steps
completed by the CLI.

### Generate / Verify buttons
The tool window (plugin) and toolbar (app) gain **Generate** and **Verify** buttons. Each offers
*Whole project* / *Current file*, mapped to the matching configured command.

Where the live output appears differs by host:
- **Plugin** — the command runs through IntelliJ's own execution layer, so its output streams into the
  **Run tool window** with full ANSI colouring and a **Stop** button.
- **App** — the command runs in the background; the latest output line shows in the toolbar **status
  chip**, and the complete output is available in a separate, expandable **log window** (like the
  diagnostics window).

After a run the golden list auto-refreshes. The standalone app refreshes both built-in variants after
Verify; it does not perform a global source switch.

### Accepting generated output
An **Accept** action promotes a generated screenshot to its committed golden. It **moves** the generated
file onto the golden: it writes the generated bytes to the golden path, takes the golden's name, and
deletes the leftover generated file. After Accept the list and preview refresh so the accepted change no
longer shows as changed.

Accept is only offered while viewing the **Test output** source (verify output such as Roborazzi's
`_actual` files). Generation frameworks overwrite the golden directly, so there is nothing to promote in
that case.

In the standalone Current file scope, **Accept all (N)** promotes every differing Test output
associated with the selected source file, independent of grid selection or filters. It confirms the
destructive operation, reports progress, continues through per-file failures, and can retry only the
unresolved subset. It is absent from Project changes.

### Completion notifications
Both hosts persist a default-on **Notify when tasks finish** setting. A notification is eligible only
when the host is in the background and a Generate/Verify command succeeds or fails; cancellation is
silent. The plugin uses the IntelliJ notification API and offers an action that activates the Run
tool window. The macOS app delivers through Notification Center while retaining its in-app status
chip and command log.

### Automatic PNG refresh
Both hosts watch the configured golden and generated-output directories for PNG changes. A burst of
filesystem events is debounced into one update. Rewriting an existing PNG invalidates only that
golden's thumbnail, status and comparison; creating, deleting or moving a PNG performs an atomic
soft-rescan that preserves the current list, selection and preview until the replacement state is
ready. Generate/Verify runs suppress intermediate watcher refreshes and perform one update when the
command finishes.

The plugin uses IntelliJ's native VFS event stream. The standalone app uses the JDK's blocking
`WatchService`, which performs no polling while idle. Non-PNG changes and paths outside the configured
directories are ignored.

## Settings / directories
Golden dirs and generated test-output dirs are stored per project (`ScreenshotSettings`, a project-level
`PersistentStateComponent`). First run: a "Choose screenshots directory" button in the header for
goldens. Later: Settings → Tools → Golden Diff, or the "Directories…" button. Multiple
directories are supported for both lists. The standalone app and its CLI also accept golden-directory
regex entries (`regex:<pattern>` in storage, or `--golden-dir-regex` in the CLI), matched against complete
project-relative directory paths. This lets one entry cover the same golden layout in many modules.
The generated-output regex, the golden matching mode, the
annotated-function regex, the file/class golden regex, and excluded golden suffixes (comma-separated;
default `_compare, _actual`) are stored in the same project settings.

The standalone app exposes Settings both in-window and from the native application menu with `⌘,`;
the menu remains available before a project is opened, while project-dependent controls display an
open-project requirement.

## Tool independence
Nothing is Roborazzi-specific except the default `_compare`/`_actual` exclusion (harmless for other
tools). Works with Paparazzi, Compose Preview Screenshot Testing, Shot, etc. — as long as goldens are
PNGs committed to git and can be matched by the configured annotation and filename patterns.
