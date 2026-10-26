---
title: Comparing screenshots
nav_order: 5
---

# Comparing screenshots

Once a golden is selected, Golden Diff loads the **committed (git HEAD)** version and derives the
local comparison source automatically. This page covers source selection, accepting generated
output, the four comparison modes, zoom, and the single-preview states.

## Automatic comparison source

Each golden card can carry independent **Working copy** and **Test output** variants. There is no
global source dropdown. Golden Diff chooses, in order:

1. An actionable, differing **Test output** from verification tests.
2. The **Working copy** when it differs from HEAD.
3. The only available local variant.

Generated roots are indexed once per refresh. Golden Diff prefers the same relative directory, then
the strongest directory-suffix match. If equally ranked candidates remain, the card reports
**Ambiguous Test output** and blocks comparison and acceptance instead of guessing.

> **Test output** requires configured generated-output directories and, if needed, a filename regex.
> See the [settings reference](settings-reference.md). The regex maps generated files to existing
> goldens; it does **not** decide which goldens appear.

## Accepting Test output

**Accept** promotes the exact generated file currently displayed to the selected golden. Before
writing, Golden Diff validates the captured source path, size, modification time, and SHA-256, so a
file changed after confirmation cannot silently overwrite a golden.

**Accept all (N)** is scoped to the selected View. It freezes the current actionable set, processes
each item, and reports successes and failures. After acceptance consumes the Test output, comparison
falls back to Working copy automatically. Figma reference cards are review-only and cannot be
accepted.

## The four comparison modes

When the golden and the comparison source differ, the mode row is enabled with four options.

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
changed”** readout summarizes the difference. When both images exist but have different dimensions,
both are downscaled to the smaller shared size before diffing so size-only differences do not
dominate the heatmap.

![Diff mode](images/compare-diff.png)
<!-- TODO(screenshot): diff mode showing the magenta heatmap and the "% pixels changed" readout -->

## Zoom

The **Zoom:** control is always available: **Fit / 50% / 75% / 100% / 150% / 200% / 400%**. It applies
to every mode and to the single preview; zoomed-in content scrolls. The zoom level persists as you move
between files and modes.

The action in the opposite corner opens a modeless, resizable comparison window. It follows live
golden/source changes while keeping its own comparison mode and zoom.

## Single-preview states

Sometimes there is nothing to compare, so the viewer shows a single image (or none) with an explanatory
status and hides the mode toggle:

| Status | Meaning |
|---|---|
| **No changes vs HEAD — <file>** | The golden is byte-for-byte identical to the comparison source. |
| **New file (not in git HEAD) — <name>** | The image is not committed yet, so there is no HEAD version. |
| **Working copy missing — showing HEAD.** | The committed golden exists but the working-copy file is gone. |
| **No generated test output found for <file>.** | No generated counterpart matched this golden. |
| **Ambiguous Test output** | Multiple equally ranked generated files matched; Compare and Accept stay disabled. |
| **Configure generated test output directories in Settings.** | No generated-output directories are configured. |
| **Could not read image.** | The file could not be decoded (for example an unsupported format). |

**Next:** [Settings reference](settings-reference.md) · [Troubleshooting & FAQ](troubleshooting.md)
