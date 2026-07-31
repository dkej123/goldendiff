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

## Developer (DEV) vs release builds

Every local build — and ordinary CI — is a **developer build**. The plugin and the app write
`build.developer=true` into their generated `golden-diff-telemetry.properties`, which:

- forces telemetry **fully offline**: the generated properties carry **empty Amplitude and Sentry
  keys regardless of `gradle.properties` or `-P` arguments**, and the host code adds a second layer
  (empty effective consent, no consent prompt, no Sentry exception bridge), so no installation id,
  event, span or exception report is ever produced;
- skips the update check (the effective release channel is `DEV`, and both update checkers return
  before any network access);
- shows a **`DEV BUILD`** badge in the footer (and additionally **`BETA`** when the version is a
  prerelease). The telemetry checkboxes stay visible and editable and persist your choice, but only
  take effect in a release build. A stable release shows no badge.
- names the public-plugin ZIP `golden-diff-<ver>-dev.zip` (an explicit `-PdistributionSuffix=…`
  still wins).

A **release build** is opted into with `-PreleaseBuild=true`, which writes `build.developer=false`,
injects the real telemetry keys, drops the `-dev` suffix, and re-enables the update check and (with
consent) telemetry. The publish workflows pass it automatically; to reproduce one locally:

```bash
./gradlew :public-plugin:buildPlugin -PreleaseBuild=true      # golden-diff-<ver>.zip
./gradlew :app:packageDmg -PreleaseBuild=true -PappJavaHome=<full JDK 21+>
```

`:internal-plugin:runIde` loads the developer (offline, DEV-badged) public plugin as-is.

## Releasing the public plugin

The stable and beta channels use the same plugin ID:

```bash
# Stable: creates a GitHub release and publishes to the default Marketplace channel.
git tag v2.0.0
git push origin v2.0.0

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
git tag app-v2.0.0
git push origin app-v2.0.0

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

## Release observability checklist

Treat Sentry as part of every public-plugin and desktop-app release. Before creating either stable or
beta tags, list the repository's Actions secrets and confirm that all three names exist:

```bash
gh secret list --repo dkej123/goldendiff
# required: SENTRY_APP_DSN, SENTRY_PLUGIN_DSN, SENTRY_AUTH_TOKEN
```

`SENTRY_AUTH_TOKEN` must be a CI-only token with the minimum release-management scope described in
[sentry-setup.md](sentry-setup.md#release-checklist). Never print the secret, pass it as a command-line
argument, commit it, or embed it in an artifact. Stop the release if any required secret is absent.

After pushing a public-plugin or app tag:

1. Wait for the complete workflow, then inspect the **Create Sentry release** step. It must be
   `success`, not `skipped`.
2. Confirm the matching Sentry release exists: `golden-diff-plugin@<pluginVersion>` for `v*` and
   `beta-v*`, or `golden-diff-app@<pluginVersion>` for `app-v*` and `app-beta-v*`.
3. Confirm its environment is `stable` or `beta` according to the tag, its release URL points to the
   published artifact/listing, and commits are associated through the release HEAD.

This check is mandatory because both release workflows intentionally guard the Sentry action with
`SENTRY_AUTH_TOKEN != ''`: if the secret is missing, GitHub marks the step `skipped` while the
Marketplace/GitHub/Homebrew publication can still finish successfully. If this happens, set the
secret and backfill only the missing Sentry release and deploy as described in
[sentry-setup.md](sentry-setup.md#backfilling-a-missed-release). Do **not** rerun the entire publish
workflow: that can attempt to upload an already-published Marketplace version or recreate release
assets.

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
