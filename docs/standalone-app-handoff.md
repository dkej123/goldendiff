# Standalone app — state of play and what is left

Handoff note for whoever picks this up next. Branch: **`feat/core-extraction-standalone-app`**
(10 commits ahead of `main`). Read [gotchas.md](gotchas.md) before touching the build.

## Why this exists

Golden Diff was IDE-only: matching, git access and pixel diffing were tangled into the Swing tool
window, so none of it could run elsewhere. The goal is a desktop application on macOS/Windows/Linux
that shares its logic — and eventually its UI — with the plugins, without forking anything.

## Decisions already made (do not re-litigate without a reason)

| Decision | Outcome | Why |
|---|---|---|
| Kotlin Multiplatform for `:core`? | **No** — plain `kotlin-jvm` | Every consumer is a JVM. KMP would add `expect/actual` around `File`, `BufferedImage`, `ImageIO`, git and unlock no platform. Revisit only if a wasm/native target becomes real. |
| KMP for the plugin module itself? | **Impossible** | IJPGP looks for `compileKotlin`/`jar`; KMP produces `compileKotlinJvm`/`jvmJar` and the plugin ZIP builds **without Kotlin classes**, silently. [IJPGP #1507](https://github.com/JetBrains/intellij-platform-gradle-plugin/issues/1507), open, deprioritised. |
| Plugin UI toolkit | **Compose + Jewel**, floor raised to 2025.1 | Lets one UI serve the plugin and the app. Accepted cost below. |
| `PixelDiff` | Ported to `IntArray` (`ArgbImage`) | Frees the diff from AWT so a Skia renderer can use it. Behaviour unchanged. |
| Figma in the app | **No** | Figma stays plugin-only; `:app` mirrors the public plugin. |
| Windows/Linux | Written, **not built or verified** | No machines to test on. Every OS-specific branch exists so enabling them is a CI change, not a code hunt. |

**Accepted cost of the Compose decision.** Jewel is experimental
(*"binary compatibility is not guaranteed across releases"*) and its artifacts are versioned per
platform build (`jewel-ide-laf-bridge:251.x`). The tool window now uses Compose/Jewel, so both plugins
are bounded to `251.*` and must be rebuilt and tested before moving to another IDE branch.

## What is done

Modules: `:core`, `:core-ui`, `:public-plugin`, `:internal-plugin`, `:app`.

- **`:core`** — all tool-agnostic logic, 25 files, no IntelliJ/Swing/Compose: matching
  (`GoldenFinder`, `Screen`, `GenericScreenExtractor`), git (`GitCli`, `HeadBytesSource`,
  `WorkingCopyStatus`), diffing (`PixelDiff`, `ArgbImage`, `ImageLayout`, `TransparentBorder`),
  scanning (`ChangeScanner`), navigation (`ProjectFileIndex`, `FuzzyFileMatcher`), config
  (`GoldenDiffConfig`), platform (`Os`, `RevealInFileManager`).
- **`:core-ui`** — `CompareCanvas.kt`: two-up, swipe, onion-skin and single-image canvases in Compose.
  Compose is `compileOnly`.
- **`:app`** — working desktop app: project picker, changed-golden list, project file list, quick-open
  (`⇧⌘O` / `Ctrl+Shift+N`), four comparison modes, working-copy/test-output switch, blocked-state
  messages (no git / not a repo / no golden dirs). Config under `~/.config/golden-diff`.
- **Packaging** — `:app:packageDmg` produces a 61 MB `.dmg` with an embedded runtime.
  `.github/workflows/package-app.yml` is a one-entry matrix plus `:core:test` on Linux.
- **109 tests**, none skipped.

Verified by hand: the app launches and stays up; the `.dmg` mounts and contains a bundle with its own
`libjvm`; `core.jar` is inside `golden-diff-*.zip` and absent from `golden-diff-figma-*.zip`; no
Compose or Skiko leaked into either plugin ZIP.

Two real bugs surfaced while extracting code under test:

1. `git status --porcelain` collapses untracked directories → a brand-new golden folder showed as
   *no changes*. **Fixed separately on `main` as 1.4.3**, and reaches this branch through `GitCli`.
2. A macOS case-insensitive filesystem made an ordering test assert nothing. `ProjectFileIndex.PATH_ORDER`
   is public so ordering is tested against the comparator instead.

## What is left

### 1. Migrate the plugin UI to Compose + Jewel — done

The plugin tool window is hosted in `JewelComposePanel`; the list, controls and comparison viewer are
Compose/Jewel, and the four canvases come from `:core-ui`. The platform supplies Jewel, Compose and
Skiko through `bundledModule` / `<module>` declarations, and the old Swing viewer/list classes are
deleted. Both plugins are bounded to `251.*`.

**`ScreenshotConfigurable` stays in Swing.** This is deliberate: it keeps `ExtraSettingsComponent`
  returning `JComponent`, so `internal-plugin` never touches Compose and the two-plugin split is
  unaffected. Compose and Swing coexist fine here.

Version alignment is the sharpest risk: `:core-ui` compiles against one Compose, the plugin gets the
platform's, `:app` ships its own. Keep the IJP build number in `gradle.properties` driving both the
`bundledModule` versions and the `jewel-int-ui-standalone:<jewel>-<ijp-build>` suffix in `:app`.

### 2. Decouple `ExtraComparisonSource` from IntelliJ

Deliberately skipped — it is the seam for Figma, which the app does not have, so today it buys only
speculation. When wanted: replace `Project` with a `GoldenDiffContext` (project root + config) and
`ScreenshotSettings` with `GoldenDiffConfig`, keep `createSettingsComponent` returning `JComponent`,
and move the pure types (`ExtraComparisonResult`, `ExtraFirstRunDefaults`) to `:core`. Mechanical
across the 8 `compare/Figma*` files; no logic changes.

### 3. macOS signing and notarization — blocks first real use

Without a Developer ID certificate Gatekeeper rejects the `.dmg` as damaged. Either buy one and wire
it into `package-app.yml`, or document `xattr -dr com.apple.quarantine` in the install instructions.
Decide before handing the app to anyone.

### 4. App gaps

- The "project files" pane is a flat list, not the expandable tree the plan called for. Jewel's
  `LazyTree` is the intended component once the app moves onto Jewel.
- Quick-open has no keyboard navigation — arrows/Enter are not wired, only clicking.
- No context menu (reveal in Finder, copy path, delete). `RevealInFileManager` already exists in
  `:core` and is unused.
- No settings screen beyond adding directories; match mode, regexes and excluded suffixes can only be
  changed by hand-editing the properties file.
- Thumbnails are not rendered in the golden list.

### 5. Windows and Linux

Nothing to write, only to verify: add the two matrix entries in `package-app.yml`, then actually test.
The unverified branches are `RevealInFileManager` (`explorer /select,` and `xdg-open`) and the
`Ctrl+Shift+N` shortcut. Watch for a machine without `git` on `PATH` — the app is supposed to say so
plainly rather than show an empty list; that path is coded but only exercised by trimming `PATH`.

## Before merging to `main`

- `:internal-plugin:runIde` on a real project — nobody has yet confirmed by hand that the Figma
  extension still registers after the `:core` extraction. Unit tests cannot cover the classloader
  arrangement.
- Decide the release story: the app versions off `pluginVersion` today, which couples it to the
  plugin's cadence for no particular reason.
- `CHANGELOG.md` has an `[Unreleased]` section to turn into a real entry.
