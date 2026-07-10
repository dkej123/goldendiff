# Features & behavior

## Tool window
Right-anchored, id "Golden Diff". Left: header + golden list. Right: the comparison viewer.

## Matching goldens to the current file
- Refresh is triggered by **file selection changes only** (not caret moves), debounced ~300 ms.
- `CurrentScreen` builds caret-independent names: declared class names + the file base name +
  functions annotated with annotations matched by the configured annotation regex (default
  `.*Preview.*|Test`, excluding `PreviewParameter`) + `test*` functions. Plain `@Composable` helpers
  are intentionally ignored because small names such as `Stat` or `Content` create noisy false
  positives.
- `GoldenFinder.find` lists PNGs in the configured dirs, using one of two mutually exclusive
  `MatchMode`s (chosen with a radio in Settings). Matching is done against each golden's path
  **relative to its root** (with `/` separators), so layouts that nest the class or package as
  directories still match.
  - **Match by annotated method** (default): a golden matches when its relative path *contains* the
    name of an annotated / `test*` function as a whole word or camel-case segment. This covers the
    default naming of every major framework (Roborazzi, Paparazzi, Compose Preview, Shot), where the
    method name is always part of the golden filename.
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
revision). The "Compare" switch chooses the right side:
- **Working copy** — the selected golden file on disk (default behavior).
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
- **Onion skin** — selected comparison source overlaid on HEAD; a slider blends opacity and the view
  labels the base/overlay roles.
- **Diff** — pixel heatmap (`PixelDiff` → `DiffPanel`): unchanged pixels dimmed to grayscale context,
  changed pixels highlighted in magenta (opacity scales with the per-pixel change), plus a
  "% pixels changed" readout. When both images exist but have different dimensions, both are
  downscaled to the smaller shared size before diffing so size-only differences do not dominate the
  heatmap. Areas present in only one image count as changed when there is no counterpart image.
- **Zoom** combo (always visible): `Fit / 50% / 75% / 100% / 150% / 200% / 400%`. Applies to all modes
  and the single view; zoomed-in content scrolls. Zoom level persists across files and modes.

## Settings / directories
Golden dirs and generated test-output dirs are stored per project (`ScreenshotSettings`, a project-level
`PersistentStateComponent`). First run: a "Choose screenshots directory" button in the header for
goldens. Later: Settings → Tools → Golden Diff, or the "Directories…" button. Multiple
directories are supported for both lists. The generated-output regex, the golden matching mode, the
annotated-function regex, the file/class golden regex, and excluded golden suffixes (comma-separated;
default `_compare, _actual`) are stored in the same project settings.

## Tool independence
Nothing is Roborazzi-specific except the default `_compare`/`_actual` exclusion (harmless for other
tools). Works with Paparazzi, Compose Preview Screenshot Testing, Shot, etc. — as long as goldens are
PNGs committed to git and can be matched by the configured annotation and filename patterns.
