# Framework guide: Android · Roborazzi

Read this when the project's screenshot tests use **Roborazzi** (`io.github.takahirom.roborazzi`). It
states what to set on the **GoldenDiff side** and the exact **commands** to configure so a reviewer can
generate, verify and accept goldens from the tool window. Commands below are **examples** — inspect the
project for its real module and task names; they are not fixed.

## Detect this framework if ANY holds
- A module applies the `io.github.takahirom.roborazzi` Gradle plugin (see `build.gradle(.kts)` /
  `libs.versions.toml`).
- Gradle exposes `recordRoborazzi*`, `verifyRoborazzi*` or `compareRoborazzi*` tasks.
- Committed golden PNGs sit under a screenshot-test module (often `src/test/screenshots`), and a
  verify run drops `*_actual.png` / `*_compare.png` under `build/outputs/roborazzi/`.

## How the framework behaves (why the setup below is needed)
`record` **writes the goldens in place**. `verify` does **not** touch goldens: on a mismatch it writes
the freshly rendered image as `<name>_actual.png` (and a side-by-side `<name>_compare.png`) under
`<module>/build/outputs/roborazzi/`. GoldenDiff compares HEAD against that `_actual` output as **Test
output**, and its **Accept** action promotes the `_actual` onto the golden (Roborazzi's `verify` never
does this itself), so a reviewer can accept a single change without re-recording everything.

## GoldenDiff side — directories
Point GoldenDiff at the committed goldens and the verify output. Example for a `:screenshotTests`
module (adjust the module/paths to the project):
```
--golden-dir     screenshotTests/src/test/screenshots     # committed baselines
--generated-dir  screenshotTests/build/outputs/roborazzi   # verify _actual/_compare output
--match-mode     annotated                                 # goldens are named after @Preview functions
--annotation-regex '.*Preview.*'
```
The default generated regex `^(.+)_actual\.png$` already selects Roborazzi's `_actual` files and maps
them back to the golden's base name; `_compare` is excluded by the default excluded suffixes.

## GoldenDiff side — commands
Configure the four command templates so the tool-window buttons work. Example (replace `:screenshotTests`
and the `Debug` variant with the project's actual task names):
```
--generate-all-cmd  './gradlew :screenshotTests:recordRoborazziDebug'
--verify-all-cmd    './gradlew :screenshotTests:verifyRoborazziDebug'
--generate-file-cmd './gradlew :screenshotTests:recordRoborazziDebug --tests "*{file_name}Kt*"'
--verify-file-cmd   './gradlew :screenshotTests:verifyRoborazziDebug --tests "*{file_name}Kt*"'
```
The per-file filter targets the **declaring `<FileName>Kt` class** of the top-level `@Preview` functions
in the edited Kotlin file — not a hand-written test class. When previews are auto-generated into sharded
test classes (`generateComposePreviewRobolectricTests`), matching the `…Kt` class is the reliable scope,
which is why `{file_name}` (not `{class_name}`) is used here. If the project scopes runs differently
(e.g. a custom task property), derive the real filter from its build config instead.

## Verify
Do not run a recording command as part of automated configuration. Tell the user they can later change a
composable so a preview differs, click **Verify** (whole project or current file) in GoldenDiff, watch
the run in the IDE's Run tool window, then review the `_actual` output as **Test output** and **Accept**
the ones they want. A non-zero verify exit with `_actual` files present is the normal "there are
differences" outcome, not a setup error.
