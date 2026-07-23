<!-- AGENTS.md is a symlink to this file — keep the content tool-neutral (Claude Code, Codex, …). -->
# Golden Diff — agent guide

Android Studio / IntelliJ plugin (Kotlin). In a tool window it lists screenshot-test goldens for the
screen/class you are editing and compares the **git HEAD** version with the **working copy** or
generated test output in four modes: side-by-side, swipe, onion skin, pixel-diff. Tool-agnostic (Roborazzi,
Paparazzi, Compose Preview Screenshot, Shot…) — the user points it at golden and optional generated
output directories.

## Fast facts
- **Five Gradle modules.** `:core` (tool-agnostic logic — matching, git, pixel diff, file index; **no
  IntelliJ, no UI**), `:core-ui` (app-only comparison canvases in Compose, **Compose is `compileOnly`**),
  `:public-plugin` (Swing UI), `:internal-plugin`, `:app` (Compose desktop application). The plugins
  and app share `:core`; the app is built and supported on **macOS only** for now, with Windows/Linux
  branches written but unverified.
- **Two Gradle modules → two plugins.** `:public-plugin` = **Golden Diff**
  (`com.github.dkwasniak.goldendiff`, zip `golden-diff-<ver>.zip`), published to Marketplace.
  `:internal-plugin` = **Golden Diff — Figma** (`com.github.dkwasniak.goldendiff.figma`, zip
  `golden-diff-figma-<ver>.zip`), a dependent plugin (`<depends>` on the public one) with only the
  Figma feature, distributed through an internal custom plugin repository — NOT Marketplace. The
  internal plugin contributes its comparison source through the public plugin's `comparisonSource`
  extension point. (Golden Diff was "Screenshot Compare" / `…screenshotcompare` — re-listed as a new
  Marketplace plugin under the new ID; new numeric ID assigned on first upload.)
- Plugin target platform: **IntelliJ Platform 2024.1+ (build 241+)**, compiled against 2024.1 with no
  upper build limit. Plugin classes and packaged `core.jar` emit Java 17 bytecode; the Compose app and
  `:core-ui` remain on Java 21.
- Toolchain: **JDK 21** (bytecode 21 — 2025.1+ runs on JBR 21), **Gradle 9.6.1**, Kotlin **2.2.20**,
  IntelliJ Platform Gradle Plugin **2.17.0**, Compose Multiplatform **1.8.2**.

## Common commands
```bash
./gradlew :core:test                     # the bulk of the logic tests — run these first, they are fast
./gradlew :public-plugin:buildPlugin     # golden-diff-<ver>.zip → Marketplace
./gradlew :internal-plugin:buildPlugin   # golden-diff-figma-<ver>.zip → custom repo
./gradlew :internal-plugin:runIde        # sandbox IDE (IntelliJ, NOT AS) with BOTH plugins loaded
./gradlew :app:run                       # standalone desktop app
./gradlew :app:packageDmg -PappJavaHome=<full JDK 21+>   # .dmg installer
```
`packageDmg` needs a JDK that ships `jpackage`. **A JetBrains Runtime does not** — if Gradle runs on
Android Studio's JBR (the common case here) it fails with `'jpackage' is missing`. Pass `-PappJavaHome`
or set `JAVA_HOME` to a full JDK; `:app:run` and the tests are unaffected.
Install into AS: Settings → Plugins → ⚙ → Install Plugin from Disk → the built zip → restart.

## Release cycle
- **Internal (Figma) plugin — automated.** Bump `pluginVersion` in `gradle.properties`, commit, and
  push to `main`. The `publish-internal.yml` workflow rebuilds it and commits the hosted files under
  `distribution/` (ZIP + `updatePlugins.xml`, served via `raw.githubusercontent.com`). No manual step;
  `./distribution/publish.sh` is only the local fallback. The team installs it once from the custom
  repo (see [docs/installation.md](docs/installation.md)) and then auto-updates.
- **Public plugin — Marketplace.** Tag `v<ver>` to trigger `release.yml` / `publish-plugin.yml`.
- **Public plugin beta — Marketplace.** Work on `beta`; tag `beta-v<ver>` to publish the same plugin
  ID to the Marketplace `beta` channel. Testers add
  `https://plugins.jetbrains.com/plugins/beta/32662`.
- **Desktop app.** `app-v<ver>` publishes Stable and updates `golden-diff`; `app-beta-v<ver>` creates
  a GitHub prerelease and updates `golden-diff@beta`. Both casks live on `main`.
- Details: [docs/build-and-run.md](docs/build-and-run.md#distributing-the-internal-plugin).

## Before you touch anything, read
- [docs/architecture.md](docs/architecture.md) — modules, data flow, key classes.
- [docs/build-and-run.md](docs/build-and-run.md) — build/run details, platform compatibility.
- [docs/features.md](docs/features.md) — exact behavior (matching, compare modes, zoom, test output).
- [docs/gotchas.md](docs/gotchas.md) — non-obvious traps. **Read this before build changes.**
- [docs/roadmap.md](docs/roadmap.md) — limitations, planned work, good first tasks.
- [docs/standalone-app-handoff.md](docs/standalone-app-handoff.md) — **start here if you are
  continuing the standalone-app work**: decisions already settled, what is done, what is left.

## Working agreements
- Keep new code in the style of the surrounding files (see architecture doc for package layout).
- **`:core` must never depend on the IntelliJ Platform, Swing or Compose.** It backs both the plugins
  and the standalone app; anything IDE-specific belongs in `:public-plugin`, anything visual in
  `:core-ui`. AWT types (`BufferedImage`, `ImageIO`) are fine — they are JDK, not Swing.
- **Dependency direction on `:core` is asymmetric on purpose.** `:public-plugin` uses `implementation`
  so `core.jar` is packaged into the plugin ZIP; `:internal-plugin` uses `compileOnly` because it
  resolves core classes through the public plugin's classloader (its parent). Getting this wrong
  compiles fine and only fails at runtime — check with
  `unzip -l public-plugin/build/distributions/golden-diff-*.zip`.
- **Compose is `compileOnly` in app-only `:core-ui`.** The app ships its own runtime; neither plugin
  may depend on `:core-ui` or package Compose/Skiko/Jewel.
- The plugin tool window is Swing and its open-ended 241+ range relies on stable platform APIs plus
  Kotlin PSI and Git4Idea. Do not add Kotlin Analysis API calls, so K2 support remains valid.
- After any build, **read the Gradle log for `BUILD SUCCESSFUL/FAILED`** — do not trust a piped exit
  code (see gotchas: `| tail` hides the real status).
- Commit messages and release notes must not mention AI tools or assistants, including Codex or Claude.
