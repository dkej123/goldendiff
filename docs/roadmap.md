# Roadmap & known limitations

Status: working MVP. Below is what's intentionally missing and where to extend.

## Known limitations
- **Image formats**: only what `ImageIO` decodes (PNG/JPG/GIF/BMP). SVG / WebP goldens won't render.
- **Matching is name-based**: a golden must contain the class / preview / test name in its file name.
  Unusual naming conventions can miss.
- **No live update on edits**: the list refreshes on file switch, not while you type. Use **Refresh**
  after (re)recording goldens.
- **Comparison is HEAD vs working copy only** — no picking an arbitrary branch/revision yet.
- **`runIde` launches IntelliJ IDEA**, not Android Studio (see docs/build-and-run.md).

## Planned / nice-to-have
- [ ] Read Roborazzi `build/test-results/roborazzi/**/results-summary.json` (`golden_file_path`,
      `context_data.roborazzi_description_class`) for more precise matching when present.
- [ ] Make the `_compare` / `_actual` exclusion configurable (per tool).
- [ ] Pixel-diff highlight mode (heatmap of changed pixels) as a 4th compare mode.
- [ ] Selectable comparison base (HEAD / index / branch / another file) via the VCS API.
- [ ] Opt-in "follow caret" selection (was removed — see gotchas — but could return behind a setting).
- [ ] SVG / WebP decoding (extra decoders).
- [ ] Plugin + tool-window icons.
- [ ] Tests: unit-test `GoldenFinder`, `CurrentScreen`, `GitImageSource`, `ImagePainting`.
- [x] Publish to JetBrains Marketplace — live at
      [plugin 32653](https://plugins.jetbrains.com/plugin/32653-screenshot-compare).
- [ ] Optional `androidStudio(...)` target for in-AS `runIde`.

## Next release — 0.2.0 (proposed)
First post-Marketplace iteration. Scoped to high-value, low-risk items that stay within the
stable-platform / Kotlin-PSI / Git4Idea API constraint:

1. **Pixel-diff highlight mode** — a 4th compare mode showing a heatmap of changed pixels. Highest
   user-visible value; pure image math, no new platform APIs.
2. **Configurable exclusion suffixes** — move the hard-coded `_compare` / `_actual` exclusion into
   `ScreenshotSettings` / `ScreenshotConfigurable` (listed as a good first task below).
3. **First unit tests** — `GoldenFinder`, `CurrentScreen`, `ImagePainting`. Establishes a test
   baseline before behavior grows.
4. **Plugin + tool-window icons** — small, improves the Marketplace listing and tool window polish.

Deferred to a later release (bigger surface / new APIs): selectable comparison base (branch/index),
Roborazzi `results-summary.json` matcher, SVG/WebP decoding, `androidStudio(...)` runIde target.

## Good first tasks
- Add the results-summary.json reader as an optional matcher in `match/` (keep name matching as the
  fallback).
- Add a small settings toggle for the exclusion suffixes in `ScreenshotSettings` /
  `ScreenshotConfigurable`.
