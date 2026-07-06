---
title: Comparing screenshots
nav_order: 5
---

# Comparing screenshots

Once a golden is selected, Golden Diff loads the **committed (git HEAD)** version and compares it
against a **comparison source** of your choice. This page covers the source switch, the four comparison
modes, zoom, and the single-preview states.

## Comparison source

The **Compare:** dropdown in the header chooses what HEAD is compared against:

- **Working copy** *(default)* — the selected golden file as it currently is on disk. This is what you
  use to review a change to a golden before committing it.
- **Test output** — a freshly **generated** screenshot from your verification tests (for example a
  Roborazzi `_actual.png`). Golden Diff looks in the configured generated-output directories: first in
  the same relative directory as the golden, then anywhere under those roots, and matches files with the
  **generated output filename regex** (default `^(.+)_actual\.png$`, which maps `LoginScreen_actual.png`
  back to the golden `LoginScreen.png`).

> **Test output** requires you to configure generated-output directories and, if needed, the filename
> regex. See the [settings reference](settings-reference.md). The regex is used **only** to map a
> selected golden to its generated counterpart — it does **not** affect which goldens appear in the
> list.

## The four comparison modes

When the golden and the comparison source differ, the mode toggle appears with four options.

### Side by side
HEAD and the selected source are shown next to each other, each fit into its half and labeled.

![Side-by-side mode](images/compare-side-by-side.png)
<!-- TODO(screenshot): side-by-side mode, HEAD on the left, Working copy on the right -->

### Swipe
Both images are drawn in the same frame. Drag the vertical divider to reveal **HEAD on the left** and
the **selected source on the right**, with labels over the image.

![Swipe mode](images/compare-swipe.png)
<!-- TODO(screenshot): swipe mode with the divider partway across -->

### Onion skin
The selected source is overlaid on HEAD, and a slider blends the opacity between them — handy for
spotting small shifts.

![Onion-skin mode](images/compare-onion-skin.png)
<!-- TODO(screenshot): onion-skin mode with the opacity slider around 50% -->

### Diff
A pixel heatmap: unchanged pixels are dimmed to grayscale context, changed pixels are highlighted in
**magenta** (the highlight strength scales with how much each pixel changed), and a **“% pixels
changed”** readout summarizes the difference. Areas present in only one of the two images count as
changed.

![Diff mode](images/compare-diff.png)
<!-- TODO(screenshot): diff mode showing the magenta heatmap and the "% pixels changed" readout -->

## Zoom

The **Zoom:** control is always available: **Fit / 50% / 75% / 100% / 150% / 200% / 400%**. It applies
to every mode and to the single preview; zoomed-in content scrolls. The zoom level persists as you move
between files and modes.

## Single-preview states

Sometimes there is nothing to compare, so the viewer shows a single image (or none) with an explanatory
status and hides the mode toggle:

| Status | Meaning |
|---|---|
| **No changes vs HEAD — <file>** | The golden is byte-for-byte identical to the comparison source. |
| **New file (not in git HEAD) — <name>** | The image is not committed yet, so there is no HEAD version. |
| **Working copy missing — showing HEAD.** | The committed golden exists but the working-copy file is gone. |
| **No generated test output found for <file>.** | *Test output* mode is on but no matching generated file was found. |
| **Configure generated test output directories in Settings.** | *Test output* mode is on but no generated-output directories are configured. |
| **Could not read image.** | The file could not be decoded (for example an unsupported format). |

**Next:** [Settings reference](settings-reference.md) · [Troubleshooting & FAQ](troubleshooting.md)
