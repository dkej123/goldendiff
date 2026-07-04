# Changelog

## [1.0.0] - 2026-07-04

Initial release of **Golden Diff**. Previously developed and published as "Screenshot Compare"; this is
a fresh Marketplace listing under a new plugin ID (`com.github.dkwasniak.goldendiff`).

### Added
- Tool window that lists screenshot-test goldens matching the current Kotlin screen, preview, or test
  file, matched by class / preview / test / file name.
- Compare the committed Git HEAD golden with the working-copy golden or generated test output.
- Four compare modes: side-by-side, swipe, onion skin, and a pixel-diff heatmap (with a
  "% pixels changed" readout).
- Fit and fixed zoom levels (50–400%) with scrolling.
- Project-level settings: golden directories, generated-output directories, generated-file regex, and
  configurable excluded golden suffixes (default `_compare, _actual`).
- Compatibility with IntelliJ Platform 2024.1+ through build `254.*`; Java 17 bytecode so it loads on
  2024.1–2024.3 (JBR 17).

[1.0.0]: https://github.com/dkej123/goldendiff/releases/tag/v1.0.0
