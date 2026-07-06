---
title: Home
nav_order: 1
---

# Golden Diff — User Guide

**Golden Diff** is an Android Studio / IntelliJ plugin that lets you review **screenshot-test goldens**
without leaving the editor. Open the tool window for the screen you are working on, and it lists the
matching goldens and compares the **committed (git HEAD)** version against your **working copy** or
generated test output — side by side, with a swipe divider, as an onion-skin blend, or as a pixel-diff
heatmap.

It is built for Android and Kotlin developers and is **tool-agnostic**: it works with PNG goldens from
**Roborazzi, Paparazzi, Compose Preview Screenshot Testing, Shot, Dropshots**, and similar tools. All it
needs is PNG goldens committed to git whose paths contain the method, class, or file name.

![The Golden Diff tool window](images/tool-window.png)
<!-- TODO(screenshot): the Golden Diff tool window — golden list on the left, side-by-side comparison on the right -->

## The tool window at a glance

The tool window is anchored to the **right edge** of the IDE and is split in two:

- **Left** — a header with controls, and the **golden list** for the file you are editing.
- **Right** — the **comparison viewer**.

Header controls:

| Control | What it does |
|---|---|
| **Choose screenshots directory** / **Directories…** | Pick your golden folder(s). The label is *Choose screenshots directory* until the plugin is configured, then *Directories…* (opens Settings). |
| **Refresh** | Rebuild the golden list for the current file — use it after (re)recording goldens. |
| **Compare:** | Choose what HEAD is compared against: **Working copy** or **Test output**. |

Comparison viewer controls (shown when the golden differs from the comparison source):

- **Mode toggle** — **Side by side**, **Swipe**, **Onion skin**, **Diff**.
- **Zoom:** — **Fit / 50% / 75% / 100% / 150% / 200% / 400%**.

## 60-second quickstart

1. Open the **Golden Diff** tool window from the right edge of the IDE.
2. Click **Choose screenshots directory** and pick the folder that holds your committed goldens.
3. Open a screen, preview, or test Kotlin file. The list fills with the goldens that match it.
4. Select a golden. The viewer shows **HEAD** vs your **Working copy**.
5. Switch modes (side by side / swipe / onion skin / diff) and zoom to inspect the change.

See [Getting started](getting-started.md) for the guided version.

## Contents

- **[Installation](installation.md)** — requirements and how to install.
- **[Getting started](getting-started.md)** — first-run setup and your first comparison.
- **[Matching goldens](matching.md)** — how the plugin finds goldens, and how to configure matching.
- **[Comparing screenshots](comparing.md)** — the compare source, the four modes, and zoom.
- **[Settings reference](settings-reference.md)** — every setting explained.
- **[Troubleshooting & FAQ](troubleshooting.md)** — fixes, limitations, and common questions.

> Looking for build-from-source or architecture docs? See the
> [contributor documentation](https://github.com/dkej123/goldendiff/tree/main/docs) and the
> [project README](https://github.com/dkej123/goldendiff#readme) on GitHub.
