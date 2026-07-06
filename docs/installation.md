---
title: Installation
nav_order: 2
---

# Installation

## Requirements

- **An IntelliJ Platform IDE**, version **2024.1 or newer** (platform build **241** through **254.\***).
  This includes recent **Android Studio** releases and JetBrains IDEs built on those platform versions.
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

## Install from a local zip

If you have a `golden-diff-<version>.zip` (for example a build you produced yourself):

1. Open **Settings → Plugins**.
2. Click the **⚙ (gear) → Install Plugin from Disk…**.
3. Select the zip file.
4. Restart the IDE.

## Compatibility notes

- Compatible with IntelliJ Platform **2024.1+ (build 241+)**, up to the declared `254.*` range,
  including Android Studio versions on those platform builds.
- The plugin only traverses Kotlin PSI (no resolve/Analysis API), so it works in both the K1 and **K2**
  Kotlin modes that recent Android Studio versions use.

> Want to build the plugin from source? See **Build from source** in the
> [project README](https://github.com/dkej123/goldendiff#readme) and the contributor
> [build & run guide](https://github.com/dkej123/goldendiff/blob/main/docs/build-and-run.md) on GitHub.

**Next:** [Getting started](getting-started.md).
