<!-- AGENTS.md is a symlink to this file — keep the content tool-neutral (Claude Code, Codex, …). -->
# Golden Diff — agent guide

Android Studio / IntelliJ plugin (Kotlin). In a tool window it lists screenshot-test goldens for the
screen/class you are editing and compares the **git HEAD** version with the **working copy** or
generated test output in four modes: side-by-side, swipe, onion skin, pixel-diff. Tool-agnostic (Roborazzi,
Paparazzi, Compose Preview Screenshot, Shot…) — the user points it at golden and optional generated
output directories.

## Fast facts
- Plugin id / package: `com.github.dkwasniak.goldendiff`. Display name: **Golden Diff**. Distribution
  zip: `golden-diff-<ver>.zip`. (Was "Screenshot Compare" / `…screenshotcompare` — re-listed as a new
  Marketplace plugin under the new ID; new numeric ID assigned on first upload.)
- Target platform: **IntelliJ Platform 2024.1+ (build 241+)**, with an open-ended `until-build`
  (no upper bound). Do not pin `untilBuild` to a concrete future branch — a non-existent version
  (e.g. `254.*` → `2025.4`) is rejected as a Marketplace configuration defect.
- Toolchain: **JDK 21**, **Gradle 9.6.1**, Kotlin **2.2.20**, IntelliJ Platform Gradle Plugin **2.17.0**.

## Common commands
```bash
./gradlew buildPlugin   # builds build/distributions/golden-diff-<ver>.zip
./gradlew runIde        # sandbox IDE (IntelliJ, NOT Android Studio) for quick testing
```
Install into AS: Settings → Plugins → ⚙ → Install Plugin from Disk → the built zip → restart.

## Before you touch anything, read
- [docs/architecture.md](docs/architecture.md) — modules, data flow, key classes.
- [docs/build-and-run.md](docs/build-and-run.md) — build/run details, platform compatibility.
- [docs/features.md](docs/features.md) — exact behavior (matching, compare modes, zoom, test output).
- [docs/gotchas.md](docs/gotchas.md) — non-obvious traps. **Read this before build changes.**
- [docs/roadmap.md](docs/roadmap.md) — limitations, planned work, good first tasks.

## Working agreements
- Keep new code in the style of the surrounding files (see architecture doc for package layout).
- Only stable platform + Kotlin-PSI + Git4Idea APIs — no Kotlin Analysis API (keeps K2 support valid).
- After any build, **read the Gradle log for `BUILD SUCCESSFUL/FAILED`** — do not trust a piped exit
  code (see gotchas: `| tail` hides the real status).
- Commit messages and release notes must not mention AI tools or assistants, including Codex or Claude.
