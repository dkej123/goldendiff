# Build & run

## Toolchain (all pinned)
- JDK **21** (Android Studio's JBR works: `/Applications/Android Studio.app/Contents/jbr/Contents/Home`).
- Gradle **9.6.1** (wrapper). Required because IntelliJ Platform Gradle Plugin 2.17.0 needs Gradle 9+.
- Kotlin JVM plugin **2.2.20**.
- IntelliJ Platform Gradle Plugin **2.17.0**.
- Plugin platform dependency: **`intellijIdeaCommunity("2024.1")`**.

Key `build.gradle.kts` bits: `instrumentCode = false`, `buildSearchableOptions = false`, and
`ideaVersion { sinceBuild = "241"; untilBuild = provider { null } }`. Plugin code and the packaged
`core.jar` emit Java 17 bytecode; the app and `:core-ui` use Java 21.

## Modules
Two Gradle modules produce two separate plugins (see [architecture.md](architecture.md#two-plugin-layout)):
- **`:public-plugin`** — Golden Diff, published to JetBrains Marketplace.
- **`:internal-plugin`** — Golden Diff — Figma, a dependent plugin (`<depends>` on the public one)
  distributed through an internal custom plugin repository.

## Commands
```bash
./gradlew :public-plugin:buildPlugin     # public-plugin/build/distributions/golden-diff-<ver>.zip
./gradlew :internal-plugin:buildPlugin   # internal-plugin/build/distributions/golden-diff-figma-<ver>.zip
./gradlew :public-plugin:verifyPlugin    # plugin structure verifier (public)
./gradlew :public-plugin:runIde          # sandbox IntelliJ IDEA with the public plugin only
./gradlew :internal-plugin:runIde        # sandbox IDEA with BOTH plugins (internal pulls in public)
```

First build downloads the platform — slow, run it in the background and
be patient. Subsequent builds are seconds (platform is cached).

Use `:public-plugin:buildPlugin` for Marketplace releases.

## Releasing the public plugin

The stable and beta channels use the same plugin ID:

```bash
# Stable: creates a GitHub release and publishes to the default Marketplace channel.
git tag v1.5.0
git push origin v1.5.0

# Beta: publishes only to the Marketplace beta channel.
git tag beta-v1.5.0-beta.1
git push origin beta-v1.5.0-beta.1
```

Both tag versions must match `pluginVersion`. For a manual beta upload, run the `Publish Plugin`
workflow and choose **beta**, or build locally and upload the ZIP with the Marketplace channel set to
`beta`. Testers add `https://plugins.jetbrains.com/plugins/beta/32662` under **Manage Plugin
Repositories**.

## Releasing the standalone macOS app and Homebrew Cask

Push a tag matching `pluginVersion` from `gradle.properties`:

```bash
git tag app-v1.4.3
git push origin app-v1.4.3

# Beta releases use a prerelease tag.
git tag app-beta-v1.5.0-beta.1
git push origin app-beta-v1.5.0-beta.1
```

The `package-app.yml` workflow builds the DMG with a full JDK, publishes it to the tag's GitHub
Release as `Golden-Diff-<version>.dmg`, calculates its SHA-256 and commits the appropriate cask to
`main`. Stable tags update `Casks/golden-diff.rb`; beta tags create a GitHub prerelease and update
`Casks/golden-diff@beta.rb`. The tag and `pluginVersion` must match or the release fails before
uploading.

`appPackageVersion` is the numeric version written into the native macOS package. It is intentionally
separate because `jpackage`/macOS package metadata does not accept a prerelease suffix such as
`-beta.1`; the release tag, DMG asset, and Homebrew cask still use the full `pluginVersion`. A manual
workflow run builds an Actions artifact but intentionally does not publish a release or change a cask.

Users install it with:

```bash
brew tap dkej123/goldendiff https://github.com/dkej123/goldendiff.git
brew install --cask dkej123/goldendiff/golden-diff
brew install --cask dkej123/goldendiff/golden-diff@beta
```

The stable and beta casks conflict because both install `Golden Diff.app`; uninstall one before
switching channels.

## Distributing the internal plugin
The internal plugin ships through a **custom plugin repository** — a static `updatePlugins.xml`
descriptor plus the ZIP. Both are hosted straight from this **public** GitHub repo: they are
committed under [`distribution/`](../distribution/) and served over `raw.githubusercontent.com` (no
GitHub release, no auth — the repo is public, so raw URLs are reachable directly).

Publishing is **automated**: the [`publish-internal.yml`](../.github/workflows/publish-internal.yml)
workflow rebuilds the internal plugin and commits refreshed `distribution/{ZIP,updatePlugins.xml}`
back to `main` on every push that changes the sources or `gradle.properties`. So the normal release
flow is just:
```bash
# bump pluginVersion in gradle.properties, then
git commit -am "…" && git push        # CI refreshes distribution/ for you
```
The bot commit only touches `distribution/` and is pushed with `GITHUB_TOKEN` (which does not
retrigger workflows), so there is no loop.

To do it locally instead (or to preview), run `./distribution/publish.sh`: it runs
`:internal-plugin:buildPlugin` + `generateUpdatePluginsXml` (the descriptor's ZIP url defaults to
`https://raw.githubusercontent.com/dkej123/goldendiff/main/distribution`; override with
`-PcustomRepoBaseUrl` if the repo/branch/path changes) and copies both files into `distribution/`,
keeping only the current ZIP.

### Team install (one-time)
Settings → Plugins → ⚙ → **Manage Plugin Repositories** → add
`https://raw.githubusercontent.com/dkej123/goldendiff/main/distribution/updatePlugins.xml`, then
install *Golden Diff — Figma* from the Marketplace tab (the IDE prompts to install the required
*Golden Diff* from Marketplace if missing). Updates then arrive automatically on every push that bumps
the version; the public plugin updates independently from Marketplace.

## Installing into Android Studio / IntelliJ
The dev target is IntelliJ IDEA. The tool window uses Swing and stable platform APIs; Kotlin PSI and
Git4Idea provide the editor and VCS integration. To install the built plugin:
1. Settings → Plugins → ⚙ → **Install Plugin from Disk…** → the zip in `build/distributions/`.
2. Restart Android Studio.
Changing `<id>` counts as a new plugin, so an old-id build must be uninstalled first.

## Running the sandbox inside real Android Studio (optional)
Swap `intellijIdeaCommunity("2024.1")` for `androidStudio("<version>")` in `build.gradle.kts`. Heavier download
and the AS version must be resolvable by the Gradle plugin. Not needed for normal development.
