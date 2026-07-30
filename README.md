# Golden Diff

[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/32662-golden-diff.svg?label=Marketplace)](https://plugins.jetbrains.com/plugin/32662-golden-diff)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/32662-golden-diff.svg)](https://plugins.jetbrains.com/plugin/32662-golden-diff)
[![Rating](https://img.shields.io/jetbrains/plugin/r/rating/32662-golden-diff.svg)](https://plugins.jetbrains.com/plugin/32662-golden-diff/reviews)

An Android Studio / IntelliJ plugin that helps you review **screenshot-test goldens** without leaving
the editor. Its snapshot-aware **Files → Goldens → Compare** workflow follows the file open in the
editor, groups matching snapshots by View and Case, and compares the **committed (git HEAD)** version
against your **working copy** or generated test output in four ways:

- **Side by side** — old and new next to each other.
- **Swipe** — drag a divider to reveal old vs new in the same frame.
- **Onion skin** — blend between them with an opacity slider.
- **Diff** — pixel heatmap highlighting exactly what changed, with a "% pixels changed" readout.

Plus a **zoom** control (Fit / 50–400%) with scrolling. When a golden is identical to the selected
comparison source, the comparison is skipped and you just get a single preview labeled
*"No changes vs HEAD"*.

## Features
- A project-wide **Folder → View → Snapshot case** tree with search, status filters, failing-view
  counts, editor-tab synchronization, and selection-preserving refreshes.
- Matches goldens to the file you're editing in two modes: by **annotated preview/test method**, or by
  a **file/class regex** (`{file_name}` / `{class_name}`). Matching runs against each golden's path, so
  nested (package/class-as-directory) layouts work too.
- **Live preview** in Settings shows how many and which goldens match the current file for your config.
- Shows Working-copy and Test-output variants together and automatically compares the actionable Test
  output, then Working copy, without a global source switch.
- Runs project-, View-, and Case-scoped **Generate / Verify** commands in the IDE Run tool window, with
  streamed output, cancellation, completion notifications, and automatic refresh.
- **Accept** promotes the exact displayed Test output to its golden; **Accept all** handles every
  actionable Test output in the selected View and reports partial failures.
- Indexes generated-output roots once per refresh and reports ambiguous matches instead of choosing an
  arbitrary same-named file.
- Offers side-by-side, swipe, onion-skin, and pixel-diff comparison modes.
- Supports a detachable, live-updating comparison window.
- Keeps screenshot directories project-local.
- Supports IntelliJ Platform 2024.1 and newer (build 241+) with no upper build limit.

## Works with any screenshot tool
It only needs PNG goldens committed to git, whose paths contain the method, class, or file name. That
covers **Roborazzi, Paparazzi, Compose Preview Screenshot Testing, Shot, Dropshots**, and similar — just
point the plugin at the golden directory.

## Requirements
Golden Diff needs two things from the project:

1. **PNG goldens committed to git**, with the method, class, or file name in each golden's path.
2. **A stable directory for verify output.** To compare a golden against *fresh* test output (not just
   your working copy), the screenshot tests must write their generated/verify images to a **fixed,
   project-relative directory** you can point Golden Diff at — **not** a per-run temporary directory.
   Configure that directory as Golden Diff's *generated* source and set the generated-file regex to
   match the files that land there.

### Example: Roborazzi (Android)
Roborazzi records goldens into its `outputDir` and, on `verifyRoborazzi`, writes the freshly rendered
image next to each golden as `<name>_actual.png` (plus a `_compare.png`). Both land in a stable,
project-local directory, so no extra setup is needed — just give it a committed `outputDir`:

```kotlin
// screenshotTests/build.gradle.kts
roborazzi {
    outputDir.set(file("src/test/screenshots")) // committed goldens + *_actual.png verify output
}
```

Golden Diff config:
- **Golden directory**: `screenshotTests/src/test/screenshots`
- **Generated directory**: the same directory
- **Generated-file regex**: `_actual\.png$` (Roborazzi's verify suffix)
- **Match**: by annotated preview method (annotation regex `Preview`) — goldens are named
  `…ScreenKt.<Name>Preview.<qualifier>.png`

Configure those commands in Golden Diff, or run `./gradlew verifyRoborazziDebug` manually. Open the
previewed file and select the failing golden; Golden Diff automatically chooses its Test output.

### Example: iOS · swift-snapshot-testing (pointfree)
This library writes a failing test's freshly rendered image to
`$SNAPSHOT_ARTIFACTS/<TestFileName>/<name>.png`. **If `SNAPSHOT_ARTIFACTS` is unset it falls back to a
volatile simulator temp dir Golden Diff cannot target**, so you must pin it to a stable directory.
Under `xcodebuild test` the variable is only forwarded into the test runner when prefixed with
`TEST_RUNNER_`:

```sh
# shell profile or CI — absolute path, e.g. inside the repo
export TEST_RUNNER_SNAPSHOT_ARTIFACTS="<repo>/.golden-diff-generated"
```

Golden Diff config:
- **Golden directory** (regex): `.*/__Snapshots__$` — the committed reference roots
- **Generated directory**: the same absolute directory as above
- **Generated-file regex**: `^(.+)\.png$` — pointfree keeps the golden's exact name (no `_actual` suffix)
- **Match mode**: file/class — XCTest methods are unannotated, so goldens are matched by the test
  class/file name

A golden `__Snapshots__/<TestClass>/<name>.png` then maps to generated `<dir>/<TestClass>/<name>.png`.

## Install
**From JetBrains Marketplace (recommended):**
- In the IDE: **Settings → Plugins → Marketplace**, search for **Golden Diff**, click **Install**.
- Or from the web: [plugins.jetbrains.com/plugin/32662-golden-diff](https://plugins.jetbrains.com/plugin/32662-golden-diff).

**Beta channel:**
1. Open **Settings → Plugins → ⚙ → Manage Plugin Repositories…** and add
   `https://plugins.jetbrains.com/plugins/beta/32662`.
2. Return to **Marketplace**, search for **Golden Diff**, and install or update it.

**From a local zip:**
1. Build the plugin (see below) or grab `public-plugin/build/distributions/golden-diff-<ver>.zip`.
2. **Settings → Plugins → ⚙ → Install Plugin from Disk…** → select the zip.
3. Restart Android Studio.

Compatible with JetBrains IDEs based on IntelliJ Platform **2024.1 and newer (build 241+)**,
including compatible Android Studio releases.

### Standalone macOS app via Homebrew

Install the stable desktop release:

```bash
brew tap dkej123/goldendiff https://github.com/dkej123/goldendiff.git
brew install --cask dkej123/goldendiff/golden-diff
```

To follow preview releases, install the separate beta channel:

```bash
brew install --cask dkej123/goldendiff/golden-diff@beta
```

The stable and beta casks conflict because both install `Golden Diff.app`; uninstall the active cask
before switching channels.

The app is currently ad-hoc signed and not notarized because the project does not use a paid Apple
Developer ID certificate. Homebrew verifies the release's SHA-256 checksum, but macOS still
quarantines the downloaded app and blocks its first launch. If you trust this repository, remove that
quarantine attribute after installation:

```bash
xattr -dr com.apple.quarantine "/Applications/Golden Diff.app"
```

Use `brew upgrade --cask golden-diff` or `brew upgrade --cask golden-diff@beta` for later builds.

## Use
1. Open the **Golden Diff** tool window (right edge).
2. First run: click **Choose screenshots directory** and pick your golden folder
   (later: Settings → Tools → Golden Diff, or the **Directories…** button). Multiple folders OK.
3. Optional: pick the matching mode (annotated method vs file/class regex) and check the live preview;
   configure generated test-output directories, filename regex, and Generate/Verify commands.
4. Pick a View or Case in **Files**, then a card in **Goldens**. **Compare** automatically shows the
   useful Working-copy or Test-output variant.
5. Run Generate/Verify from the header, review the result in any comparison mode, then use **Accept**
   or View-scoped **Accept all** when the generated output is correct.

The generated-file regex does not decide which goldens appear in the list. It filters and maps files
from the generated-output directories after goldens are indexed. The default `^(.+)_actual\.png$`
matches `Foo_actual.png` and maps it to `Foo.png`; unresolved basename/path ties remain visibly
ambiguous and cannot be compared or accepted.

## Documentation
Full end-user docs are published as a site at
**[dkej123.github.io/goldendiff](https://dkej123.github.io/goldendiff/)** (source in [`docs/`](docs/)):
[installation](docs/installation.md), [getting started](docs/getting-started.md),
[matching goldens](docs/matching.md), [comparing screenshots](docs/comparing.md),
[settings reference](docs/settings-reference.md), and
[troubleshooting & FAQ](docs/troubleshooting.md). See also the
[privacy policy](docs/privacy.md).

## Build from source
```bash
./gradlew buildPlugin   # -> build/distributions/golden-diff-<ver>.zip
./gradlew runIde        # sandbox IDE for quick testing
```
Requires JDK 21 (the Gradle wrapper handles Gradle 9.6.1). The first build downloads the IntelliJ
platform (~2 GB) and is slow; later builds take seconds.

## For Contributors
Deeper docs live in [`docs/`](docs/): [architecture](docs/architecture.md),
[build & run](docs/build-and-run.md), [features](docs/features.md), [gotchas](docs/gotchas.md),
[roadmap](docs/roadmap.md), [marketplace](docs/marketplace.md).

## License
MIT. See [LICENSE](LICENSE).
