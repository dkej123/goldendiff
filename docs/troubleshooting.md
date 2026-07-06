---
title: Troubleshooting & FAQ
nav_order: 7
---

# Troubleshooting & FAQ

## “No screenshots found for this file”

If the golden list is empty for a file you expect to match, check these in order (the same list appears
in [Matching goldens](matching.md)):

1. The **golden directory** points at the folder that contains committed PNG goldens.
2. The **matching mode** matches your project (annotated method vs file/class regex).
3. In **annotated-method** mode: the annotation regex matches the annotations your screenshot tests use,
   and the golden filenames actually contain the function name.
4. In **file/class-regex** mode: the patterns match your actual golden paths.
5. The **excluded suffixes** do not accidentally exclude real goldens.
6. The **generated file regex** is not involved in listing goldens; it only maps selected goldens to test
   output.

The fastest way to iterate is the **Preview (live)** panel in
[Settings](settings-reference.md#preview-live): it re-runs matching against the open file as you edit the
settings.

## The list didn’t update after I re-recorded goldens

The list refreshes when you **switch files**, not while you type or when files change on disk in the
background. After recording or re-recording goldens, click **Refresh** in the tool window header.

## My golden is a new file and there’s no comparison

If the golden is not committed to git yet, there is no HEAD version to compare against, so you get a
single preview labeled **“New file (not in git HEAD)”**. Commit the golden (or its previous version) to
enable comparisons.

## Test output mode shows nothing

- **“Configure generated test output directories in Settings.”** — add your generated-output folders in
  [Settings](settings-reference.md#generated-test-output-directories).
- **“No generated test output found for <file>.”** — the directories are set but no file matched the
  **generated output filename regex**. Confirm the regex’s first capture group yields the golden’s base
  name (default `^(.+)_actual\.png$`).

## Known limitations

- **Image formats:** only what Java ImageIO decodes (PNG, JPG, GIF, BMP). **SVG and WebP goldens will
  not render.**
- **No live update while editing:** the list refreshes on file switch, not as you type — use **Refresh**
  after (re)recording.
- **Comparison base is fixed:** Golden Diff compares git **HEAD** against the working copy or generated
  test output. Picking an arbitrary branch or revision is not supported yet.

## FAQ

**Does my project need to use git?**
Yes. Comparisons are against the committed (git HEAD) version, so goldens must be committed PNGs in a git
repository.

**Which screenshot tools are supported?**
Any tool that writes PNG goldens into git whose paths contain the method, class, or file name. That
covers Roborazzi, Paparazzi, Compose Preview Screenshot Testing, Shot, Dropshots, and similar tools —
just point the plugin at the golden directory.

**My screenshot tests use a custom annotation. Will matching still work?**
Yes — add your annotation to the **Screenshot annotation name regex**, e.g.
`.*Preview.*|Test|ScreenshotTest`. See [Matching goldens](matching.md).

**My goldens are stored with the class or package as sub-directories. Is that a problem?**
No. Matching runs against each golden’s path **relative to its directory**, so nested layouts (class or
package as folders) still match.

**Can I use several golden or generated-output directories?**
Yes. Both lists accept multiple directories.

**Where are my settings stored?**
Per project. They do not carry over to other projects.

**Back to:** [User Guide home](index.md)
