# Features & behavior

## Tool window
Right-anchored, id "Screenshot Compare". Left: header + golden list. Right: the comparison viewer.

## Matching goldens to the current file
- Refresh is triggered by **file selection changes only** (not caret moves), debounced ~300 ms.
- `CurrentScreen` builds a caret-independent `names` set: declared class names + preview/test/composable
  function names (`@Composable`/`@Preview`/`@Test` or `test*`) + the file base name.
- `GoldenFinder.find` lists PNGs in the configured dirs whose name contains any candidate name,
  excluding `_compare` / `_actual` artifacts. Results are sorted with the caret-function match first.
- The list is rebuilt only when the name set actually changes. Clicking around the same file keeps the
  list and the user's manual selection intact. `caretName` is used only for the *initial* selection
  when a file is first shown.

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

## Three modes (+ zoom)
- **Side by side** — HEAD | selected comparison source, each fit into its half, labeled.
- **Swipe** — both drawn in the same rect; drag the vertical divider to reveal HEAD on the left and
  the selected comparison source on the right, with labels over the image.
- **Onion skin** — selected comparison source overlaid on HEAD; a slider blends opacity and the view
  labels the base/overlay roles.
- **Zoom** combo (always visible): `Fit / 50% / 75% / 100% / 150% / 200% / 400%`. Applies to all modes
  and the single view; zoomed-in content scrolls. Zoom level persists across files and modes.

## Settings / directories
Golden dirs and generated test-output dirs are stored per project (`ScreenshotSettings`, a project-level
`PersistentStateComponent`). First run: a "Choose screenshots directory" button in the header for
goldens. Later: Settings → Tools → Screenshot Compare, or the "Directories…" button. Multiple
directories are supported for both lists. The generated-output regex is stored in the same project
settings.

## Tool independence
Nothing is Roborazzi-specific except the name and the `_compare`/`_actual` exclusion (harmless for
other tools). Works with Paparazzi, Compose Preview Screenshot Testing, Shot, etc. — as long as goldens
are PNGs committed to git and the file name contains the class/preview/test name.
