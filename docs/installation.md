---
title: Installation
nav_order: 2
---

# Installation

## Requirements

- **An IntelliJ Platform IDE** based on **2024.1 or newer** (platform build **241+**), including
  compatible Android Studio releases. There is no declared upper build limit.
- **Git.** Golden Diff compares each golden against its committed (git HEAD) version, so your project
  must be a git repository and your goldens must be **committed PNG files**. The bundled **Git**
  integration (Git4Idea) and the **Kotlin** plugin are required; both ship with Android Studio and
  IntelliJ IDEA by default.
- **PNG goldens.** The plugin renders the image formats that Java's ImageIO decodes (PNG, JPG, GIF,
  BMP). PNG is the standard output of every supported screenshot tool.

## Install from JetBrains Marketplace (recommended)

- **In the IDE:** open **Settings → Plugins → Marketplace**, search for **Golden Diff**, and click
  **Install**. Restart the IDE if prompted.
- **From the web:** [plugins.jetbrains.com/plugin/32662-golden-diff](https://plugins.jetbrains.com/plugin/32662-golden-diff).

![Golden Diff on the Plugins Marketplace](images/install-marketplace.png)
<!-- TODO(screenshot): Settings → Plugins → Marketplace with "Golden Diff" found and the Install button -->

## Install the Marketplace beta

Beta builds use the same plugin ID and settings as the stable release, but are published through the
JetBrains Marketplace `beta` channel:

1. Open **Settings → Plugins → ⚙ (gear) → Manage Plugin Repositories…**.
2. Add:
   ```
   https://plugins.jetbrains.com/plugins/beta/32662
   ```
3. Return to the **Marketplace** tab, search for **Golden Diff**, and install or update it.

Custom Marketplace repositories take precedence over the default stable repository. While the beta
repository is configured, the IDE follows the beta channel. To leave it, remove the repository URL and
install the current stable Golden Diff version.

## Install from a local zip

If you have a `golden-diff-<version>.zip` (for example a build you produced yourself):

1. Open **Settings → Plugins**.
2. Click the **⚙ (gear) → Install Plugin from Disk…**.
3. Select the zip file.
4. Restart the IDE.

## Install the standalone macOS beta with Homebrew

The standalone desktop app is currently available **only as a beta**. There is no Stable desktop cask
yet; Stable currently refers to the IDE plugin release channel. Copy and run:

```bash
brew tap dkej123/goldendiff https://github.com/dkej123/goldendiff.git && brew install --cask dkej123/goldendiff/golden-diff@beta
```

Golden Diff is currently ad-hoc signed and not notarized because the project does not use a paid Apple
Developer ID certificate. Homebrew verifies the immutable DMG against the SHA-256 checksum stored in
the cask, but Gatekeeper does not treat that checksum as an Apple developer identity. macOS therefore
quarantines the downloaded application and can block its first launch.

If you trust this repository, remove only Golden Diff's quarantine attribute after installation:

```bash
xattr -dr com.apple.quarantine "/Applications/Golden Diff.app"
```

The DMG includes the Java runtime, so users do not need to install a JDK. Update or remove the beta
with:

```bash
brew upgrade --cask golden-diff@beta
brew uninstall --cask golden-diff@beta
```

## Install the internal Figma add-on (team only)

*Golden Diff — Figma* is a separate, team-internal plugin that adds a **Figma** comparison source on
top of Golden Diff. It is not on the Marketplace; it is served from a custom plugin repository and
depends on the public *Golden Diff* plugin.

1. **Settings → Plugins → ⚙ (gear) → Manage Plugin Repositories…**, click **+**, and add:
   ```
   https://raw.githubusercontent.com/dkej123/goldendiff/main/distribution/updatePlugins.xml
   ```
2. Open the **Marketplace** tab, search for **Golden Diff — Figma**, and click **Install**. If the
   base *Golden Diff* plugin is missing, the IDE offers to install it from Marketplace first — accept.
3. Restart the IDE.

Updates are automatic: whenever a new version is published you get the usual plugin-update
notification. You only add the repository URL once. (No account or password — the repository is public
and read-only.)

## Compatibility notes

- Compatible with IntelliJ Platform **2024.1 and newer (build 241+)**. The Swing UI and stable
  platform integrations do not require a branch-specific build.
- The plugin only traverses Kotlin PSI (no resolve/Analysis API), so it works in both the K1 and **K2**
  Kotlin modes that recent Android Studio versions use.

> Want to build the plugin from source? See **Build from source** in the
> [project README](https://github.com/dkej123/goldendiff#readme) and the contributor
> [build & run guide](https://github.com/dkej123/goldendiff/blob/main/docs/build-and-run.md) on GitHub.

**Next:** [Getting started](getting-started.md).
