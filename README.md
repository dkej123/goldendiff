# Golden Diff

[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/32662-golden-diff.svg?label=Marketplace)](https://plugins.jetbrains.com/plugin/32662-golden-diff)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/32662-golden-diff.svg)](https://plugins.jetbrains.com/plugin/32662-golden-diff)
[![Rating](https://img.shields.io/jetbrains/plugin/r/rating/32662-golden-diff.svg)](https://plugins.jetbrains.com/plugin/32662-golden-diff/reviews)

An Android Studio / IntelliJ plugin that helps you review **screenshot-test goldens** without leaving
the editor. Open the tool window for the screen you're working on and it lists the matching goldens,
then compares the **committed (git HEAD)** version against your **working copy** or generated test
output in four ways:

- **Side by side** — old and new next to each other.
- **Swipe** — drag a divider to reveal old vs new in the same frame.
- **Onion skin** — blend between them with an opacity slider.
- **Diff** — pixel heatmap highlighting exactly what changed, with a "% pixels changed" readout.

Plus a **zoom** control (Fit / 50–400%) with scrolling. When a golden is identical to the selected
comparison source, the comparison is skipped and you just get a single preview labeled
*"No changes vs HEAD"*.

## Features
- Matches goldens to the file you're editing in two modes: by **annotated preview/test method**, or by
  a **file/class regex** (`{file_name}` / `{class_name}`). Matching runs against each golden's path, so
  nested (package/class-as-directory) layouts work too.
- **Live preview** in Settings shows how many and which goldens match the current file for your config.
- Compares Git HEAD with the working-copy golden.
- Compares Git HEAD with generated verification output such as `_actual.png` files. Golden matching and
  generated-output matching are separate steps: the list is built from the golden directories, then the
  generated-file regex is used only to find the generated counterpart for the selected golden in Test
  output mode.
- Offers side-by-side, swipe, onion-skin, and pixel-diff comparison modes.
- Keeps screenshot directories project-local.
- Supports IntelliJ Platform 2024.1+ through build 254.

## Works with any screenshot tool
It only needs PNG goldens committed to git, whose paths contain the method, class, or file name. That
covers **Roborazzi, Paparazzi, Compose Preview Screenshot Testing, Shot, Dropshots**, and similar — just
point the plugin at the golden directory.

## Install
**From JetBrains Marketplace (recommended):**
- In the IDE: **Settings → Plugins → Marketplace**, search for **Golden Diff**, click **Install**.
- Or from the web: [plugins.jetbrains.com/plugin/32662-golden-diff](https://plugins.jetbrains.com/plugin/32662-golden-diff).

**From a local zip:**
1. Build the plugin (see below) or grab `build/distributions/golden-diff-<ver>.zip`.
2. **Settings → Plugins → ⚙ → Install Plugin from Disk…** → select the zip.
3. Restart Android Studio.

Compatible with JetBrains IDEs based on IntelliJ Platform **2024.1+ (build 241+)**, including Android
Studio versions on those platform builds, up to the declared `254.*` range.

## Use
1. Open the **Golden Diff** tool window (right edge).
2. First run: click **Choose screenshots directory** and pick your golden folder
   (later: Settings → Tools → Golden Diff, or the **Directories…** button). Multiple folders OK.
3. Optional: pick the matching mode (annotated method vs file/class regex) and check the live preview;
   configure generated test-output directories and their filename regex.
4. Open a screen/test file → pick a golden from the list → choose Working copy or Test output →
   switch modes / zoom.

The generated-file regex does not decide which goldens appear in the list. It filters and maps files
from the generated-output directories after a golden is selected. The default `^(.+)_actual\.png$`
matches `Foo_actual.png` and maps it to the selected golden `Foo.png`.

## Documentation
Full end-user docs are published as a site at
**[dkej123.github.io/goldendiff](https://dkej123.github.io/goldendiff/)** (source in [`docs/`](docs/)):
[installation](docs/installation.md), [getting started](docs/getting-started.md),
[matching goldens](docs/matching.md), [comparing screenshots](docs/comparing.md),
[settings reference](docs/settings-reference.md), and
[troubleshooting & FAQ](docs/troubleshooting.md).

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
