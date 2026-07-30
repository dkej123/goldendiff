# Framework guide: Android · Roborazzi

Read this when the project's screenshot tests use **Roborazzi** (`io.github.takahirom.roborazzi`). It
states what to set on the **GoldenDiff side** and the exact **commands** to configure so a reviewer can
generate, verify and accept goldens from the tool window. Everything below is grounded in Roborazzi's
real behaviour, but the concrete module names, paths and task names are **examples** — inspect the
project for its own values; they vary between projects.

Official documentation: <https://takahirom.github.io/roborazzi/> (source: <https://github.com/takahirom/roborazzi>).
**If anything below is unclear or the project deviates from these examples, verify against the project's
own build config (read-only) and the official docs above before configuring — do not guess.**

## Detect this framework if ANY holds
- A module applies the `io.github.takahirom.roborazzi` Gradle plugin (see its `build.gradle(.kts)` or
  `libs.versions.toml`, e.g. `alias(libs.plugins.roborazzi)`).
- Gradle exposes `recordRoborazzi*`, `verifyRoborazzi*` or `compareRoborazzi*` tasks.
- Committed golden PNGs sit under a screenshot-test module, and a verify run drops `*_actual.png` /
  `*_compare.png` under `<module>/build/outputs/roborazzi/`.

## How the framework behaves (why the setup below is needed)
- **`record` writes the goldens in place** — into the directory Roborazzi's `outputDir` points at
  (default `<module>/build/outputs/roborazzi`; projects that commit goldens usually override it to a
  source path such as `src/test/screenshots`).
- **`verify` never touches the goldens.** On a mismatch it writes the freshly rendered image as
  `<name>_actual.png` (plus a side-by-side `<name>_compare.png`) under `<module>/build/outputs/roborazzi/`,
  regardless of where `outputDir` points. GoldenDiff compares the git-HEAD golden against that
  `_actual` file as **Test output**, and its **Accept** action promotes the `_actual` onto the golden
  (Roborazzi's `verify` never does this itself), so a reviewer can accept one change without
  re-recording everything.
- **Golden filenames embed the `@Preview` function name.** Roborazzi names each golden after the
  producer function — e.g. `com.example.PlanRouteScreenKt.PlanRouteScreenPreview.Dark_…_PIXEL_7_PRO0.png`
  — usually as a **flat** directory of fully-qualified filenames rather than per-class subfolders. This
  is why GoldenDiff's default **annotated** match mode works out of the box (see below).

## GoldenDiff side — directories
Find the golden directory from the module's `roborazzi { outputDir.set(...) }` block (or its default),
and the verify output under `build/outputs/roborazzi`. Example for a `:screenshotTests` module — adjust
to the project:
```
--golden-dir     screenshotTests/src/test/screenshots      # committed baselines (roborazzi outputDir)
--generated-dir  screenshotTests/build/outputs/roborazzi   # verify _actual/_compare output
--match-mode     annotated                                 # goldens are named after @Preview functions
```
- **Leave `--match-mode annotated`** and rely on the default annotation regex `.*Preview.*|Test` — the
  golden filename contains the `@Preview` function name, so editing `PlanRouteScreen.kt` matches every
  `…PlanRouteScreenPreview….png`. Only pass `--annotation-regex` if the project drives screenshots
  through a **custom** annotation whose name is not covered by the default (add its name, e.g.
  `.*Preview.*|Test|MyScreenshot`).
- The default generated regex `^(.+)_actual\.png$` already selects Roborazzi's `_actual` files and maps
  them back to the golden's base name; `_compare` is excluded by the default excluded suffixes. Do not
  override `--generated-regex` unless the project renames that output.
- A multi-module project may have several golden directories. Prefer one precise `--golden-dir-regex`
  over a long path list when they share a stable layout, e.g. `.*/build/outputs/roborazzi$` is **wrong**
  (that is output, not committed goldens) — target the committed source path, e.g.
  `--golden-dir-regex '.*/src/test/screenshots$'`.

## GoldenDiff side — commands
Configure the five command templates so the tool-window buttons work. Example — replace `:screenshotTests`
and the `Debug` variant with the project's actual task names (`./gradlew tasks --all | grep -i roborazzi`):
```
--generate-all-cmd  './gradlew :screenshotTests:recordRoborazziDebug'
--verify-all-cmd    './gradlew :screenshotTests:verifyRoborazziDebug -Proborazzi.cleanupOldScreenshots=true'
--generate-file-cmd './gradlew :screenshotTests:recordRoborazziDebug --tests "*{file_name}Kt*"'
--verify-file-cmd   './gradlew :screenshotTests:verifyRoborazziDebug --tests "*{file_name}Kt*"'
--verify-case-cmd   './gradlew :screenshotTests:verifyRoborazziDebug --tests "*RoborazziPreviewParameterizedTests*{snapshot_method_name}*"'
```
- Add `-Proborazzi.cleanupOldScreenshots=true` to the whole-project Verify command so a successful
  complete run removes stale `_actual` / `_compare` artifacts left by earlier failures. Do not add
  it to View/Case commands: Roborazzi cannot distinguish unrelated output from output omitted by a
  filtered test run and would delete results belonging to other Views/Cases.

**Per-file scoping depends on how the project declares tests:**
- With **auto-generated** preview tests (`generateComposePreviewRobolectricTests { enable = true }`),
  previews are sharded into synthetic classes (`RoborazziPreviewParameterizedTests0..N`) and the
  preview functions are test *parameters*, not methods. Filtering by the declaring **`<FileName>Kt`**
  class — `--tests "*{file_name}Kt*"` — is the reliable scope; that is why `{file_name}` is used here,
  not `{class_name}`.
- The optional case command therefore matches the generated
  `RoborazziPreviewParameterizedTests*` display name containing `{snapshot_method_name}`. Do not
  assume a hand-written class such as `ButtonPreviewTest`; generated previews are parameters of
  sharded synthetic classes and use Gradle's standard `--tests` filtering.
- With **hand-written** Roborazzi test classes, scope by the real class instead:
  `--tests "*{class_name}*"`.
- If the project scopes runs through a custom task property (e.g. a `-P` selector), derive the real
  filter from its build config rather than assuming `--tests`.

## Verify
Do not run a recording command as part of automated configuration. Tell the user they can later change a
composable so a preview differs, click **Verify** (whole project, View, or Case) in GoldenDiff, watch
the run in the IDE's Run tool window, then review the `_actual` output as **Test output** and **Accept**
the ones they want. A non-zero verify exit with `_actual` files present is the normal "there are
differences" outcome, not a setup error.
