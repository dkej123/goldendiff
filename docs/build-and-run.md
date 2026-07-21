# Build & run

## Toolchain (all pinned)
- JDK **21** (Android Studio's JBR works: `/Applications/Android Studio.app/Contents/jbr/Contents/Home`).
- Gradle **9.6.1** (wrapper). Required because IntelliJ Platform Gradle Plugin 2.17.0 needs Gradle 9+.
- Kotlin JVM plugin **2.2.20**.
- IntelliJ Platform Gradle Plugin **2.17.0**.
- Platform dependency: **`intellijIdeaCommunity("2024.1")`**.

Key `build.gradle.kts` bits: `instrumentCode = false` and `buildSearchableOptions = false` (no Java/
form sources, no custom searchable settings), `ideaVersion { sinceBuild = "241"; untilBuild = provider { null } }`
(open-ended upper bound — see gotchas).

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

## Distributing the internal plugin
The internal plugin ships through a **custom plugin repository** — a static `updatePlugins.xml`
descriptor plus the ZIP. Both are hosted straight from this **public** GitHub repo: they are
committed under [`distribution/`](../distribution/) and served over `raw.githubusercontent.com` (no
GitHub release, no auth — the repo is public, so raw URLs are reachable directly).

Publish a release (after bumping `pluginVersion` in `gradle.properties`):
```bash
./distribution/publish.sh          # builds, then refreshes distribution/{ZIP,updatePlugins.xml}
git add distribution && git commit && git push
```
`publish.sh` runs `:internal-plugin:buildPlugin` + `generateUpdatePluginsXml` (the descriptor's ZIP
url defaults to `https://raw.githubusercontent.com/dkej123/goldendiff/main/distribution`; override
with `-PcustomRepoBaseUrl` if the repo/branch/path changes) and copies both files into
`distribution/`. Only the current ZIP is kept there.

### Team install (one-time)
Settings → Plugins → ⚙ → **Manage Plugin Repositories** → add
`https://raw.githubusercontent.com/dkej123/goldendiff/main/distribution/updatePlugins.xml`, then
install *Golden Diff — Figma* from the Marketplace tab (the IDE prompts to install the required
*Golden Diff* from Marketplace if missing). Updates then arrive automatically on every push that bumps
the version; the public plugin updates independently from Marketplace.

## Installing into Android Studio / IntelliJ
The dev target is IntelliJ IDEA (only stable platform + Kotlin-PSI + Git4Idea APIs are used, all present
in Android Studio builds based on supported IntelliJ Platform versions). To install the built plugin:
1. Settings → Plugins → ⚙ → **Install Plugin from Disk…** → the zip in `build/distributions/`.
2. Restart Android Studio.
Changing `<id>` counts as a new plugin, so an old-id build must be uninstalled first.

## Running the sandbox inside real Android Studio (optional)
Swap `intellijIdeaCommunity("2024.1")` for `androidStudio("<version>")` in `build.gradle.kts`. Heavier download
and the AS version must be resolvable by the Gradle plugin. Not needed for normal development.
