# Features & behavior

## Tool window
The plugin is a snapshot-aware **Files → Goldens → Compare** workflow. There is no global Scope or
Working/Test selector.

- **Files** is a project-wide Folder → View → Snapshot case tree. Opening or switching editor tabs
  selects and reveals the corresponding View. Moving the caret in the same file does nothing.
- **Goldens** shows every golden associated with the selected View or Case once, with independent
  Working-copy and Test-output status chips.
- **Compare** derives the displayed source automatically and preserves mode/zoom while selection and
  layout change. Its opposite-corner action opens a modeless, resizable comparison window that
  follows the live golden selection while keeping its own comparison mode and zoom.

At wide widths Files is a full-width strip above Goldens | Compare. At medium widths it opens from a
Files rail beside the resizable Goldens | Compare split. Below 320 px the three panes become
single-pane tabs; selecting a View/Case advances to Goldens and selecting a golden advances to Compare.
Selection and comparison state survive width-class changes.

## Files, search, and status filters
The Files index contains only Views associated with at least one existing golden. A Case is an
annotated preview, test method, or supported story/function whose name matches one or more goldens.
Goldens without function-level evidence remain directly on the View row.
When the same Case name exists in multiple source files, the owner file/class encoded in the golden
path wins before the stable path-order fallback, so one golden remains attached to the intended View.

Quick Open searches every source file in the indexed project, including files with no matching
goldens. Opening such a file selects it as the active View and shows the dedicated no-snapshots state;
it does not add the file to the snapshot-only Files tree.

Search covers the view path, generator path, and case name and reveals matching descendants.
The **Failed**, **Modified**, and **New** status filters are combined with OR and match every status
represented by a view or case, not only its highest-priority badge. A view containing both Modified
and New goldens therefore remains visible under either filter. With no status selected the list shows
everything, while selecting all three still excludes unchanged views and cases.
Labeled statuses are sorted above unchanged entries. A failed unmatched golden remains visible on its
view. A view is Failed when any associated Test output is new or differs from HEAD, while Working-copy
changes remain Modified or New. Folder counters count failed views rather than methods.

Selecting a source file loads its goldens. Selecting a golden loads its comparison without opening a
new tab; it also highlights the owning source file in the Files pane, expands the grouped tree when
needed, and scrolls that row into view.

If search or filters hide the active editor View, the constraints remain intact and an
**Active file hidden by filters / Reveal \<View\>** banner appears. Reveal clears only those
constraints and reselects the active View. Initial indexing keeps the previous usable tree visible;
true-empty, no-search-results, no-filter-results and active-file-without-snapshots have distinct
states.

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
For the selected golden, both the plugin and standalone app load the committed version through the
same `GitCli` mechanism used to assign statuses. Working copy and Test output are scanned together and
one golden carries both variants. The source is
selected automatically from the merged status: a changed or new Test output (**Failed**) is compared
with HEAD; **Modified**, **New**, and **Unchanged** goldens use Working copy. If that preferred variant
is absent, the only available variant is shown.

- **Working copy** — the selected golden file on disk.
- **Test output** — a generated screenshot from configured output directories or a host-provided
  explicit artifact. Matching first tries the same relative directory as the golden, then falls back
  to all files under the configured output roots. Generated roots and explicit artifacts are indexed
  once per refresh. The standalone macOS app also discovers pointfree swift-snapshot-testing failure
  artifacts from `@+ file://` URLs in the selected Xcode `.xcresult`; it verifies that the PNG
  still exists and keeps the test-class parent directory for disambiguation. A basename/path tie is reported as
  **Ambiguous Test output** and blocks Compare/Accept rather than selecting an arbitrary file.
  Files discovered by a configured generated directory are filtered by a configurable regex. The
  default is `^(.+)_actual\.png$`, so `_actual` files are selected and `_compare` files are ignored.
  The first capture group is treated as the golden base name (`Foo_actual.png` → `Foo.png`).

  In the standalone app, Settings → Test output matching selects **Directory scan** or **Xcode test
  results (.xcresult)**. Xcode mode can auto-detect the checkout's DerivedData folder, accepts an
  explicit `<Project>-<hash>` override, and lists individual result bundles from a configurable
  48-hour window, newest first. The **Test Run** picker selects exactly one bundle; its device/OS,
  timestamp and pass/fail summary remain visible, and the selection is remembered per project.
  If that bundle disappears, the app falls back to the newest available run. After Verify, a newly
  created or updated run is selected automatically. XCResult candidates carry the exact basename
  resolved by the host and do not use or merge configured generated directories or their regex.

The generated-file regex is not part of the current-file → golden-list matching. The list always comes
from golden filenames matched against names extracted from the current Kotlin file. The regex is used
after the user selects a golden to find its generated counterpart.

- **Bytes equal** (no change vs HEAD) → single preview, status "No changes vs HEAD — <file>"; the mode
  toggle is hidden.
- **Differ** → all four modes are shown.
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
- **Fit / − / % / +** zoom controls below the canvas apply to all modes and the single view;
  zoomed-in content scrolls. Zoom level persists across files and modes.

## Running tests & accepting goldens
Beyond comparing, Golden Diff can **run the screenshot tests** and **accept generated output** — both
driven entirely by user configuration, with nothing framework-specific baked in.

### Configurable commands
Settings → Golden Diff → Commands holds five optional, free-form shell commands, stored per project:
- **Generate all goldens** — regenerate every golden for the project.
- **Generate selected View** — regenerate goldens for the selected View.
- **Verify all goldens** — run the whole verify/compare pass, producing test output.
- **Verify selected View** — verify the View's unique generators as a sequential, deduplicated batch.
- **Verify selected Case** — verify one snapshot method/preview. When blank, both hosts fall back to
  Verify selected View for that Case's generator and label the broader scope.

Any command left blank disables its corresponding button; hovering the disabled button shows a tooltip
explaining how to enable it (fill the command in Settings). View/Case commands may use placeholders
expanded from the selected View and generator before the command runs:
- `{file_name}` — the file's base name.
- `{class_name}` — the file's declared class name.
- `{file_path}` — the full path to the file.
- `{snapshot_file_name}` — the generator file's base name.
- `{snapshot_class_name}` — the generator class, falling back to its file name.
- `{snapshot_file_path}` — the project-relative generator path without its extension.
- `{snapshot_method_name}` — the test/preview function.

For Xcode test-result projects, the device picker derives its current value from the configured
commands. Choosing a simulator or connected physical device atomically replaces (or adds)
`-destination` in every non-empty Generate/Verify command. All commands must contain one unambiguous
`xcodebuild` and share the same scheme plus project/workspace; otherwise nothing is saved and the UI
asks for a consistent choice. The fast local device catalog ends the picker's loading state while
scheme-aware `xcodebuild -showdestinations` validation continues in the background. A choice made
during that validation is queued and saved only if the authoritative result confirms it. Before
every Generate/Verify scope, the app compares the chosen run's
platform, device model and exact OS with that destination. A mismatch can be cancelled or run once
anyway; missing metadata never produces a warning.

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
The tool window (plugin) and toolbar (app) expose whole-project Generate/Verify in the main header and
View/Case-scoped actions in the Goldens header.

Where the live output appears differs by host:
- **Plugin** — the command runs through IntelliJ's own execution layer, so its output streams into the
  **Run tool window** with full ANSI colouring and a **Stop** button.
- **App** — the command runs in the background; the latest output line shows in the toolbar **status
  chip**, and the complete output is available in a separate, expandable **log window** (like the
  diagnostics window).

After a run the golden list auto-refreshes. The standalone app refreshes both built-in variants after
Verify and automatically displays Test output when the refreshed result is **Failed**.

### Accepting generated output
An **Accept** action promotes a generated screenshot to its committed golden. It **moves** the generated
file onto the golden: it writes the generated bytes to the golden path, takes the golden's name, and
deletes the leftover generated file. After Accept the list and preview refresh so the accepted change no
longer shows as changed.

Accept is only offered for an automatically selected, differing **Test output** source (verify output
such as Roborazzi's `_actual` files). Generation frameworks overwrite the golden directly, so there is
nothing to promote in that case. After Accept consumes the Test output, the status and comparison
automatically return to Working copy.

**Accept all (N)** is View-scoped in both hosts and promotes every differing Test output associated
with that View, independent of selected Case, golden, search, or filters. It confirms the operation,
reports progress, continues through per-file failures, and retains the unresolved retry subset. There
is no project-wide Accept all.

### Completion notifications
Both hosts persist a default-on **Notify when tasks finish** setting. A notification is eligible only
when the host is in the background and a Generate/Verify command succeeds or fails; cancellation is
silent. The plugin uses the IntelliJ notification API and offers an action that activates the Run
tool window. The macOS app delivers through Notification Center while retaining its in-app status
chip and command log.

### Automatic PNG refresh
Both hosts watch the configured golden and generated-output directories for PNG changes. Xcode
`.xcresult` artifacts outside those roots are re-resolved on refresh rather than watched recursively.
A burst of
filesystem events is debounced into one update. Rewriting an existing PNG invalidates only that
golden's thumbnail, status and comparison; creating, deleting or moving a PNG performs an atomic
soft-rescan that preserves the current list, selection and preview until the replacement state is
ready. Generate/Verify runs suppress intermediate watcher refreshes and perform one update when the
command finishes.

The plugin uses IntelliJ's native VFS event stream. The standalone app uses the JDK's blocking
`WatchService`, which performs no polling while idle. Non-PNG changes and paths outside the configured
directories are ignored.

The standalone app also watches Git's `HEAD`, `packed-refs`, and `refs/**` metadata. A commit,
checkout, rebase, or other HEAD change is debounced, confirmed with one `git rev-parse HEAD`, and then
invalidates cached HEAD comparisons and refreshes the tree while preserving the active selection.
Unrelated Git metadata changes do not spawn Git or rescan screenshots.

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

## Optional comparison extensions

A dependent plugin may contribute reference cards through `ExtraComparisonSource`. Those cards are
shown in the same Goldens grid as Working/Test-backed goldens for the active View. Selecting a Case
limits extension cards to the matching function. A source-provided filter chip appears only when the
current View or Case contains extension cards; it is off by default and, when enabled, shows only
those cards. Leaving that scope hides and resets the filter.

The internal Figma extension contributes its five-colour mark and lists
`figma-compose-preview` functions. A missing cached reference remains visible as a
“Loading Figma…” card. Selecting it uses the existing download/cache flow and compares
`Figma ↔ Current Implementation`; failures offer Retry. Figma cards are review-only, so Accept and
Accept all continue to operate exclusively on generated Test output.
