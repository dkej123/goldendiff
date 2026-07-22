<!-- AGENTS.md is a symlink to this file — keep the content tool-neutral (Claude Code, Codex, …). -->
# Golden Diff — agent guide

Android Studio / IntelliJ plugin (Kotlin). In a tool window it lists screenshot-test goldens for the
screen/class you are editing and compares the **git HEAD** version with the **working copy** or
generated test output in four modes: side-by-side, swipe, onion skin, pixel-diff. Tool-agnostic (Roborazzi,
Paparazzi, Compose Preview Screenshot, Shot…) — the user points it at golden and optional generated
output directories.

## Fast facts
- **Five Gradle modules.** `:core` (tool-agnostic logic — matching, git, pixel diff, file index; **no
  IntelliJ, no UI**), `:core-ui` (comparison canvases in Compose, **Compose is `compileOnly`**),
  `:public-plugin`, `:internal-plugin`, `:app` (standalone desktop application). The plugins and the
  app share `:core`; the app is built and supported on **macOS only** for now, with Windows/Linux
  branches written but unverified.
- **Two Gradle modules → two plugins.** `:public-plugin` = **Golden Diff**
  (`com.github.dkwasniak.goldendiff`, zip `golden-diff-<ver>.zip`), published to Marketplace.
  `:internal-plugin` = **Golden Diff — Figma** (`com.github.dkwasniak.goldendiff.figma`, zip
  `golden-diff-figma-<ver>.zip`), a dependent plugin (`<depends>` on the public one) with only the
  Figma feature, distributed through an internal custom plugin repository — NOT Marketplace. The
  internal plugin contributes its comparison source through the public plugin's `comparisonSource`
  extension point. (Golden Diff was "Screenshot Compare" / `…screenshotcompare` — re-listed as a new
  Marketplace plugin under the new ID; new numeric ID assigned on first upload.)
- Target platform: **IntelliJ Platform 2025.1+ (build 251+)**, with an open-ended `until-build`
  (no upper bound) **while the plugin UI is still Swing**. 251 is the floor because Jewel and the
  Compose platform modules first ship bundled with the platform there. Do not pin `untilBuild` to a
  concrete future branch — a non-existent version (e.g. `254.*` → `2025.4`) is rejected as a
  Marketplace configuration defect.
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
- Details: [docs/build-and-run.md](docs/build-and-run.md#distributing-the-internal-plugin).

## Before you touch anything, read
- [docs/architecture.md](docs/architecture.md) — modules, data flow, key classes.
- [docs/build-and-run.md](docs/build-and-run.md) — build/run details, platform compatibility.
- [docs/features.md](docs/features.md) — exact behavior (matching, compare modes, zoom, test output).
- [docs/gotchas.md](docs/gotchas.md) — non-obvious traps. **Read this before build changes.**
- [docs/roadmap.md](docs/roadmap.md) — limitations, planned work, good first tasks.

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
- **Compose is `compileOnly` in `:core-ui`.** The app ships its own runtime; a plugin must take
  Compose/Skiko/Jewel from the platform, and a second copy in one process breaks the classloaders.
- Only stable platform + Kotlin-PSI + Git4Idea APIs — no Kotlin Analysis API (keeps K2 support valid).
  This still holds: the plugin UI is Swing. It stops holding the moment the tool window moves to
  Compose/Jewel, which are versioned per platform build with no binary-compatibility guarantee — at
  that point `untilBuild` has to become a bounded range.
- After any build, **read the Gradle log for `BUILD SUCCESSFUL/FAILED`** — do not trust a piped exit
  code (see gotchas: `| tail` hides the real status).
- Commit messages and release notes must not mention AI tools or assistants, including Codex or Claude.
