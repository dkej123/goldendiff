# Screenshot Compare

An Android Studio / IntelliJ plugin that helps you review **screenshot-test goldens** without leaving
the editor. Open the tool window for the screen you're working on and it lists the matching goldens,
then compares the **committed (git HEAD)** version against your **working copy** or generated test
output in three ways:

- **Side by side** — old and new next to each other.
- **Swipe** — drag a divider to reveal old vs new in the same frame.
- **Onion skin** — blend between them with an opacity slider.

Plus a **zoom** control (Fit / 50–400 %) with scrolling. When a golden is identical to the selected
comparison source, the comparison is skipped and you just get a single preview labeled
*"No changes vs HEAD"*.

## Works with any screenshot tool
It only needs PNG goldens committed to git, whose file names contain the class / preview / test name.
That covers **Roborazzi, Paparazzi, Compose Preview Screenshot Testing, Shot**, and similar — just point
the plugin at the golden directory.

## Install
1. Build the plugin (see below) or grab `build/distributions/screenshot-compare-<ver>.zip`.
2. **Settings → Plugins → ⚙ → Install Plugin from Disk…** → select the zip.
3. Restart Android Studio.

Compatible with JetBrains IDEs based on IntelliJ Platform **2024.1+ (build 241+)**, including Android
Studio versions on those platform builds, up to the declared `253.*` range.

## Use
1. Open the **Screenshot Compare** tool window (right edge).
2. First run: click **Choose screenshots directory** and pick your golden folder
   (later: Settings → Tools → Screenshot Compare, or the **Directories…** button). Multiple folders OK.
3. Optional: configure generated test-output directories and their filename regex.
4. Open a screen/test file → pick a golden from the list → choose Working copy or Test output →
   switch modes / zoom.

## Build from source
```bash
./gradlew buildPlugin   # -> build/distributions/screenshot-compare-<ver>.zip
./gradlew runIde        # sandbox IDE for quick testing
```
Requires JDK 21 (the Gradle wrapper handles Gradle 9.6.1). The first build downloads the IntelliJ
platform (~2 GB) and is slow; later builds take seconds.

## For contributors / AI agents
Deeper docs live in [`docs/`](docs/): [architecture](docs/architecture.md),
[build & run](docs/build-and-run.md), [features](docs/features.md), [gotchas](docs/gotchas.md),
[roadmap](docs/roadmap.md). Agent entry points: `CLAUDE.md` / `AGENTS.md`.
