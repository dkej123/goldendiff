# Changelog

## [Unreleased]

### Changed
- Rename the plugin to **Golden Diff** (display name, tool window, and settings entry). The plugin ID
  (`com.github.dkwasniak.screenshotcompare`) and Marketplace URL are unchanged.

## [1.1.0] - 2026-07-04

### Added
- Pixel-diff compare mode: a heatmap that dims unchanged pixels and highlights changed ones, with a
  "% pixels changed" readout.
- Configurable excluded golden suffixes (Settings → Tools → Golden Diff). Defaults to
  `_compare, _actual`; previously hard-coded.

### Changed
- Replace the deprecated `Alarm`-based refresh debounce with a plain Swing `Timer`, removing the only
  deprecated-API usage flagged by the Marketplace verifier.

### Fixed
- Emit Java 17 bytecode instead of 21 so the plugin loads on IntelliJ 2024.1–2024.3 (JBR 17), matching
  the declared `sinceBuild = 241`.

## [1.0.0] - 2026-07-03

First stable release.

### Added
- Full plugin listing description in `plugin.xml`, kept in sync with the Marketplace page on publish.

### Changed
- Promote the plugin to a stable 1.0.0 release.

## [0.1.1] - 2026-07-03

### Changed
- Extend declared compatibility through IntelliJ Platform build `254.*`.

## [0.1.0] - 2026-07-03

Initial public preview.

### Added
- Tool window that lists screenshot-test goldens matching the current Kotlin screen, preview, or test file.
- Git HEAD vs working-copy comparison for selected golden screenshots.
- Git HEAD vs generated test-output comparison with configurable output directories.
- Configurable generated-file regex, defaulting to `_actual.png` outputs.
- Side-by-side, swipe, and onion-skin comparison modes.
- Fit and fixed zoom levels with scroll support.
- Project-level settings for golden and generated-output directories.
- Compatibility range for IntelliJ Platform 2024.1+ through build `253.*`.

[1.1.0]: https://github.com/dkej123/android_studio_screenshot_compare_plugin/releases/tag/v1.1.0
[1.0.0]: https://github.com/dkej123/android_studio_screenshot_compare_plugin/releases/tag/v1.0.0
[0.1.1]: https://github.com/dkej123/android_studio_screenshot_compare_plugin/releases/tag/v0.1.1
[0.1.0]: https://github.com/dkej123/android_studio_screenshot_compare_plugin/releases/tag/v0.1.0
