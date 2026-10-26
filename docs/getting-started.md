---
title: Getting started
nav_order: 3
---

# Getting started

This page takes you from a freshly installed plugin to your first screenshot comparison.

## 1. Open the tool window

Open the **Golden Diff** tool window from the **right edge** of the IDE. On first run, choose the
directory containing your committed screenshots. The same setup is always available from
**Settings → Tools → Golden Diff**.

![First run — Choose screenshots directory](images/getting-started-first-run.png)
<!-- TODO(screenshot): tool window on first run, empty, with the "Choose screenshots directory" button -->

## 2. Point it at your goldens

Click **Choose screenshots directory** and select the folder that contains your **committed** golden
PNGs — for example the directory where Roborazzi, Paparazzi, or Compose Preview Screenshot Testing
writes its reference images.

You can add more than one directory later from Settings; see the
[settings reference](settings-reference.md).

## 3. Open a screen or test file

Open the Kotlin file for the screen, preview, or test you are working on. Golden Diff reads the file,
extracts the relevant names (class names, the file name, and annotated screenshot-function names), and
lists the goldens that match.

- When goldens are found, their Views appear in **Files** and **Review** shows the selected View's
  goldens as a thumbnail strip above the comparison.
- If nothing matches, the empty state points to directory and matching settings — see
  [Matching goldens](matching.md) to adjust how matching works.

The **Files** tab lists every snapshot-related View, grouped under folder headers that stay pinned
while you scroll and can be collapsed. Search and the Failed / Modified / New filters narrow it
without losing the active selection; use **Reveal** when the file selected in the editor is hidden by
a filter. Picking a file switches to **Review**; there, ‹ › step to the previous/next file and
`Files ▾` opens the same list as an overlay.

Golden Diff watches configured golden and generated-output directories. Image changes update
automatically; structural changes use a selection-preserving rescan. Source-code edits still do not
rematch while you type.

## 4. Review your first comparison

Select a golden in the strip. The viewer loads the **committed (HEAD)** version and automatically chooses the
useful local variant: differing Test output first, then Working copy, then the only available source.

- If they are identical, you get a single preview labeled **“No changes vs HEAD — <file>”**.
- If they differ, the mode row is enabled and you can switch between **Side**, **Swipe**,
  **Onion**, and **Diff**, and change the **Zoom**.

![Reviewing a golden](images/getting-started-comparison.png)
<!-- TODO(screenshot): a selected golden showing a side-by-side HEAD vs Working copy comparison -->

## Tour of the controls

| Area | What it is |
|---|---|
| **Files** | Project-wide Folder → View → Case list with search, status filters, and collapsible sticky folders. |
| **Review** | File stepping, the goldens strip (status bar under each thumbnail), mode row, Accept, and the comparison. |
| **Generate / Verify** | Runs configured project-, View-, or Case-scoped commands in the IDE Run tool window. |
| **Accept / Accept all** | Promotes the exact displayed Test output to one golden or every actionable golden in the View. |
| **Compare** | Shows HEAD vs the automatically derived source, with comparison modes, zoom, and a detachable window. |
| **⋮ menu** | Settings, Refresh, Accept All in File, the extension filter, and Open Comparison in Separate Window. |

## Where to go next

- Not seeing the goldens you expect? → [Matching goldens](matching.md)
- Want to compare against freshly generated test output? → [Comparing screenshots](comparing.md)
- Want to fine-tune everything? → [Settings reference](settings-reference.md)
