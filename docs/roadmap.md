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
- [ ] Publish to JetBrains Marketplace; optionally an `androidStudio(...)` target for in-AS `runIde`.

## Good first tasks
- Add the results-summary.json reader as an optional matcher in `match/` (keep name matching as the
  fallback).
- Add a small settings toggle for the exclusion suffixes in `ScreenshotSettings` /
  `ScreenshotConfigurable`.
