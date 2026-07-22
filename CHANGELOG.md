# Changelog

## [1.4.1] - 2026-07-22

### Added
- The comparison view now shows the golden's file name in a header above the preview, with a
  one-click button to copy the full name to the clipboard. Long Roborazzi-style names are shortened
  by dropping the leading fully-qualified `package.ClassName.` prefix, keeping the human-relevant
  tail (e.g. `FooPreview.Dark_PIXEL_7.png`).

## [1.4.0] - 2026-07-21

### Added
- Right-click context menu on the golden list: **Show in Finder/Explorer**, **Copy Absolute Path**,
  and **Delete** (with confirmation; supports deleting multiple selected goldens).
- Optional **Trim transparent padding around image content** setting (Settings → Tools → Golden Diff →
  Display). When enabled, fully transparent borders are cropped so images are shown tight to their
  content, in both the comparison view and the list thumbnails. Off by default — images are shown
  exactly as stored.

### Fixed
- Removed a thin checkerboard sliver that could appear along one edge of a rendered image. The image
  now fills its draw rectangle exactly instead of being re-fit with independent rounding, so the
  background no longer peeks out on wide or short screenshots.

## [1.3.1] - 2026-07-21

### Fixed
- An optional comparison source contributed by a companion plugin now appears in the tool window's
  *Compare* dropdown even when that plugin finishes loading after the tool window was first shown
  (for example when the tool window is restored open on IDE startup). The dropdown is now topped up on
  refresh instead of only when it is first built.

## [1.3.0] - 2026-07-13

### Added
- **Project changes scope.** A new *Scope* selector in the tool window header switches the list
  between the file open in the editor and every changed golden across the whole project. Only changed
  goldens are shown, sorted changed-first, then new. Works with both comparison sources: working copy
  and test output.

### Changed
- Faster golden matching in *file/class regex* mode: each pattern is compiled once per refresh instead
  of being recompiled for every candidate file, and the result sort key is precomputed. Noticeably
  cheaper on large golden trees.
- The *Project changes* working-copy view derives each screenshot's changed/new status directly from
  `git status`, so it no longer performs a per-file `git HEAD` revision read.
- The *Project changes* test-output view indexes the generated directory once for O(1) counterpart
  lookups instead of re-scanning it for every golden, and computes the `git HEAD` comparisons in
  parallel.

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

[1.3.0]: https://github.com/dkej123/goldendiff/releases/tag/v1.3.0
[1.2.0]: https://github.com/dkej123/goldendiff/releases/tag/v1.2.0
[1.1.4]: https://github.com/dkej123/goldendiff/releases/tag/v1.1.4
[1.1.3]: https://github.com/dkej123/goldendiff/releases/tag/v1.1.3
[1.1.2]: https://github.com/dkej123/goldendiff/releases/tag/v1.1.2
[1.1.1]: https://github.com/dkej123/goldendiff/releases/tag/v1.1.1
[1.1.0]: https://github.com/dkej123/goldendiff/releases/tag/v1.1.0
[1.0.0]: https://github.com/dkej123/goldendiff/releases/tag/v1.0.0
