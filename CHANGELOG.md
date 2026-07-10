# Changelog

## [1.2.0] - 2026-07-10

### Added
- Screenshot list status indicators: changed screenshots are marked with a subtle red accent, and
  new screenshots are marked with a subtle green accent.
- A compact legend explains the screenshot status indicators when changed or new screenshots are
  present.
- Thumbnail size controls in the tool window header. Reducing the thumbnail size turns the screenshot
  list into a denser wrapped grid for scanning more screenshots at once.

### Changed
- Screenshots that differ from `git HEAD` are sorted to the top of the list, followed by new
  screenshots, then unchanged screenshots.
- Comparison labels are clipped gracefully in narrow tool windows instead of overlapping.
- Pixel diff now downscales images with mismatched dimensions before comparison, so size-only
  differences do not dominate the heatmap.

## [1.1.4] - 2026-07-06

### Changed
- Replaced the deprecated `ReadAction.compute(ThrowableComputable)` call with the stable
  `runReadAction { }` helper. The former is deprecated on IntelliJ Platform build 261+; now that the
  plugin has an open-ended `until-build`, the Marketplace verifier checks it against 2026.x builds too.
  No behavior change.

## [1.1.3] - 2026-07-06

### Fixed
- Removed the fixed `until-build` upper bound (`254.*`). It pointed at a non-existent platform
  (`2025.4`), which the Marketplace verifier flagged as a configuration defect and which kept the
  plugin from showing as compatible/installable. The plugin now has an open-ended upper bound and
  loads on current and future IntelliJ / Android Studio builds; it uses only long-stable platform,
  Kotlin-PSI, and Git4Idea APIs.

## [1.1.2] - 2026-07-06

### Changed
- Golden list thumbnails now render from the full-resolution image at paint time instead of caching a
  pre-scaled, sharpened bitmap. They stay crisp on HiDPI/Retina displays and the renderer uses less
  memory.

## [1.1.1] - 2026-07-06

### Added
- Declare `dependencySupport` for Roborazzi, Paparazzi, Shot, and Dropshots so IDEs can recommend
  Golden Diff in projects that use those screenshot-test libraries (also populates the plugin's
  "Plugin Features" on the Marketplace).

## [1.1.0] - 2026-07-06

Reworked how goldens are matched to the file you are editing, plus quality-of-life fixes.

### Added
- Live **Preview** in Settings → Tools → Golden Diff: shows how many and which goldens match the file
  currently open in the editor for the settings you are editing, updating as you type.

### Changed
- Golden matching is now split into two explicit, mutually exclusive modes (picked with a radio in
  settings):
  - **Match by annotated method** — a golden matches when its path contains the name of an annotated
    (or `test*`) function. Covers the default naming of Roborazzi, Paparazzi, Compose Preview, Shot,
    and swift-snapshot-testing without configuration.
  - **Match by file/class regex** — your own regex over the golden path, with `{file_name}` and
    `{class_name}` placeholders.
- Matching now runs against each golden's path **relative to its directory**, so layouts that nest the
  class or package as sub-directories also match.
- **Breaking (config):** the old golden-pattern placeholders `{candidate}`, `{fileName}`, and
  `{className}` are replaced by `{file_name}` and `{class_name}`; re-check your golden filename regex
  after upgrading.

### Fixed
- The comparison preview no longer keeps showing the previous image when you switch to a file that has
  no goldens.
- Golden matching no longer runs while the tool window is collapsed or hidden; it refreshes when the
  window is reopened.

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

[1.2.0]: https://github.com/dkej123/goldendiff/releases/tag/v1.2.0
[1.1.4]: https://github.com/dkej123/goldendiff/releases/tag/v1.1.4
[1.1.3]: https://github.com/dkej123/goldendiff/releases/tag/v1.1.3
[1.1.2]: https://github.com/dkej123/goldendiff/releases/tag/v1.1.2
[1.1.1]: https://github.com/dkej123/goldendiff/releases/tag/v1.1.1
[1.1.0]: https://github.com/dkej123/goldendiff/releases/tag/v1.1.0
[1.0.0]: https://github.com/dkej123/goldendiff/releases/tag/v1.0.0
