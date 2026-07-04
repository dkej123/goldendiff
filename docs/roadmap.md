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
- [x] Make the `_compare` / `_actual` exclusion configurable (per tool).
- [x] Pixel-diff highlight mode (heatmap of changed pixels) as a 4th compare mode.
- [ ] Selectable comparison base (HEAD / index / branch / another file) via the VCS API.
- [ ] Opt-in "follow caret" selection (was removed — see gotchas — but could return behind a setting).
- [ ] SVG / WebP decoding (extra decoders).
- [x] Plugin + tool-window icons.
- [x] Tests: unit-test `GoldenFinder`, `ImagePainting`, `GeneratedImageSource`, `PixelDiff` (pure
      logic). `CurrentScreen` / `GitImageSource` still need a platform (`BasePlatformTestCase`) harness.
- [x] Publish to JetBrains Marketplace — live as **Golden Diff**
      ([plugin 32662](https://plugins.jetbrains.com/plugin/32662-golden-diff)).
- [ ] Optional `androidStudio(...)` target for in-AS `runIde`.

## Next release — 0.2.0 (in progress on main, unreleased)
First post-Marketplace iteration. Done and sitting on `main`:

1. ~~Pixel-diff highlight mode~~ — done (`PixelDiff` + `DiffPanel`, 4th compare mode).
2. ~~Configurable exclusion suffixes~~ — done (`ScreenshotSettings.excludedSuffixes` + settings UI).
3. ~~First unit tests~~ — done for the pure-logic classes (`GoldenFinder`, `ImagePainting`,
   `GeneratedImageSource`, `PixelDiff`); `CurrentScreen` / `GitImageSource` still need a platform test.
4. ~~Plugin + tool-window icons~~ — already shipped.

Also fixed here: Java 17 bytecode target (was 21) so the plugin loads on 2024.1–2024.3.

Candidate for the release after: platform tests for `CurrentScreen` / `GitImageSource`, selectable
comparison base (branch/index), Roborazzi `results-summary.json` matcher, SVG/WebP decoding,
`androidStudio(...)` runIde target.

## Good first tasks
- Add the results-summary.json reader as an optional matcher in `match/` (keep name matching as the
  fallback).
- Add a small settings toggle for the exclusion suffixes in `ScreenshotSettings` /
  `ScreenshotConfigurable`.
