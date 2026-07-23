# Standalone app — state of play and what is left

This note records the split between the IDE plugins and the desktop application. Read
[gotchas.md](gotchas.md) before changing the build.

## Current architecture

The hosts deliberately share logic, not UI:

- `:core` is the plain JVM source of matching, git access, change scanning, image geometry,
  transparent-border handling, pixel diffing, configuration and project indexing.
- `:public-plugin` uses Swing for its tool window and settings. It packages `core.jar`, but does not
  depend on `:core-ui`, Compose, Jewel or Skiko.
- `:internal-plugin` contributes Figma through the public `comparisonSource` extension point. It
  sees `:core` through the public plugin classloader and therefore keeps its dependency `compileOnly`.
- `:core-ui` contains the Compose comparison canvases used only by `:app`.
- `:app` is the standalone Compose desktop application. Its UI and packaging remain independent
  from the plugin UI.

The plugins compile against IntelliJ Platform 2024.1, support build 241 and newer without an upper
bound, and emit Java 17 bytecode (including packaged `core.jar`). The app and `:core-ui` stay on
JDK/bytecode 21.

## Decisions that remain settled

| Decision | Outcome | Why |
|---|---|---|
| Kotlin Multiplatform for `:core`? | No — plain `kotlin-jvm` | Every consumer is a JVM and the logic uses JVM file/image APIs. |
| Plugin UI toolkit | Swing | Stable across the open-ended 241+ platform range. |
| Desktop UI toolkit | Compose Desktop | The application ships and controls its own runtime. |
| Shared UI module | App-only `:core-ui` | Avoids coupling plugin compatibility to Compose/Jewel platform binaries. |
| `PixelDiff` representation | `IntArray` / `ArgbImage` | Both Swing/AWT and Compose/Skia can adapt it at their host boundary. |
| Figma in the app | No | Figma remains an internal plugin extension. |
| Windows/Linux | Written, not verified | Enable only after platform testing. |

## Desktop app status

The Compose app supports project selection, current-file matching, project-wide changed screenshots,
a project tree and quick-open, working-copy/test-output sources, all four comparison modes, zoom,
settings, icons and native packaging resources. Local uncommitted app changes are intentional and
must be preserved while plugin work continues.

`:app:packageDmg` needs a full JDK containing `jpackage`; Android Studio's JBR does not include it.
macOS signing and notarization still need a release decision.

The macOS app has stable and beta casks: `Casks/golden-diff.rb` and
`Casks/golden-diff@beta.rb`. Tags named `app-v<pluginVersion>` create normal GitHub Releases; tags
named `app-beta-v<pluginVersion>` create prereleases. The workflow publishes the DMG and updates the
matching cask's version and SHA-256 on `main`. Keep the release asset name
`Golden-Diff-<pluginVersion>.dmg`; it is part of both Cask URL contracts.

`appPackageVersion` stays numeric for native macOS metadata while `pluginVersion` may carry a
prerelease suffix. The stable and beta casks conflict because they both install `Golden Diff.app`.

## Plugin status

The public plugin uses the Swing `ScreenshotPanel`, `GoldenCellRenderer`, `CompareView`, four
comparison panels, single-image panel, zoom controls and the Java tool-window factory. These are thin
host adapters over `:core` logic. The Figma extension contract and project settings format are
unchanged.

Before a release, verify:

- `:core:test`, `:app:test`, `:public-plugin:test`, and `:internal-plugin:test`;
- both plugin ZIP builds and `:public-plugin:verifyPlugin`;
- `core.jar` occurs only in the public ZIP;
- neither ZIP contains Compose, Jewel or Skiko;
- plugin classes and packaged `core.jar` have class-file major version at most 61 (Java 17);
- patched descriptors contain `since-build="241"` and no `until-build`;
- `:internal-plugin:runIde` loads both plugins and registers the Figma source;
- the desktop app still launches with its current Compose UI.
