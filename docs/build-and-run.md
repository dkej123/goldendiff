# Build & run

## Toolchain (all pinned)
- JDK **21** (Android Studio's JBR works: `/Applications/Android Studio.app/Contents/jbr/Contents/Home`).
- Gradle **9.6.1** (wrapper). Required because IntelliJ Platform Gradle Plugin 2.17.0 needs Gradle 9+.
- Kotlin JVM plugin **2.2.20**.
- IntelliJ Platform Gradle Plugin **2.17.0**.
- Platform dependency: **`intellijIdeaCommunity("2025.1")`**.

Key `build.gradle.kts` bits: `instrumentCode = false` and `buildSearchableOptions = false` (no Java/
form sources, no custom searchable settings), `ideaVersion { sinceBuild = "251"; untilBuild = "251.*" }`.
The bounded range is required by the branch-specific platform Compose/Jewel modules; see gotchas.

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
The dev target is IntelliJ IDEA. The tool window uses platform-bundled Compose/Jewel; Kotlin PSI and
Git4Idea provide the editor and VCS integration. To install the built plugin:
1. Settings → Plugins → ⚙ → **Install Plugin from Disk…** → the zip in `build/distributions/`.
2. Restart Android Studio.
Changing `<id>` counts as a new plugin, so an old-id build must be uninstalled first.

## Running the sandbox inside real Android Studio (optional)
Swap `intellijIdeaCommunity("2025.1")` for `androidStudio("<version>")` in `build.gradle.kts`. Heavier download
and the AS version must be resolvable by the Gradle plugin. Not needed for normal development.
