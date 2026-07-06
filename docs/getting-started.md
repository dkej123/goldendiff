---
title: Getting started
nav_order: 3
---

# Getting started

This page takes you from a freshly installed plugin to your first screenshot comparison.

## 1. Open the tool window

Open the **Golden Diff** tool window from the **right edge** of the IDE. On the first run it shows a
single button, **Choose screenshots directory**, and the status **“Choose a screenshots directory to
begin.”**

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
extracts the relevant names (class names, the file name, and annotated / `test*` function names), and
lists the goldens that match.

- When goldens are found, the status reads **“N screenshot(s) found.”** and the list fills with
  thumbnails.
- If nothing matches, the status reads **“No screenshots found for this file. Check the directory or
  record screenshots.”** — see [Matching goldens](matching.md) to adjust how matching works.

The list refreshes when you **switch files**, not while you type. After you re-record goldens, click
**Refresh** to rebuild the list.

## 4. Review your first comparison

Select a golden in the list. The viewer loads the **committed (HEAD)** version and compares it against
your **Working copy** (the golden currently on disk):

- If they are identical, you get a single preview labeled **“No changes vs HEAD — <file>”**.
- If they differ, the mode toggle appears and you can switch between **Side by side**, **Swipe**,
  **Onion skin**, and **Diff**, and change the **Zoom**.

![Reviewing a golden](images/getting-started-comparison.png)
<!-- TODO(screenshot): a selected golden showing a side-by-side HEAD vs Working copy comparison -->

## Tour of the controls

| Area | What it is |
|---|---|
| **Directories… / Choose screenshots directory** | Opens the folder picker (first run) or Settings (once configured). |
| **Refresh** | Rebuilds the list for the current file. Use after recording goldens. |
| **Compare:** | Switches the right-hand side of the comparison between **Working copy** and **Test output**. |
| **Golden list** (left) | The goldens matching the current file; the caret’s function is preferred for the initial selection. |
| **Comparison viewer** (right) | Shows HEAD vs the selected source, with the mode toggle and zoom. |

## Where to go next

- Not seeing the goldens you expect? → [Matching goldens](matching.md)
- Want to compare against freshly generated test output? → [Comparing screenshots](comparing.md)
- Want to fine-tune everything? → [Settings reference](settings-reference.md)
