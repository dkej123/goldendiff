---
title: Settings reference
nav_order: 6
---

# Settings reference

All settings live in **Settings → Tools → Golden Diff** (you can also open them with the **Directories…**
button in the tool window header). Settings are stored **per project**.

![The Golden Diff settings page](images/settings.png)
<!-- TODO(screenshot): the full Settings → Tools → Golden Diff page -->

The page is validated when you apply it: an invalid regex is rejected with a message such as
*“Generated file regex is invalid: …”*, and nothing is saved until it is fixed.

## Golden directories
> *Committed screenshot baselines listed in the tool window.*

The folders that contain your committed golden PNGs. Add one or more with the **+** button; remove with
**−**. This is the only required setting — until at least one directory is set, the tool window shows
**“Choose a screenshots directory to begin.”**

**Default:** empty.

## Generated test output directories
> *Fresh screenshots produced by verification tests; used only when Compare is set to Test output.*

The folders your screenshot tool writes freshly generated images to (for example Roborazzi `_actual`
outputs). Only used when **Compare:** is set to **Test output**.

**Default:** empty.

## Generated output filename regex
> *Used only after you select a golden and choose Compare: Test output. The first capture group must be
> the matching golden basename.*

Maps a generated file back to its golden. The **first capture group** is treated as the golden’s base
name. For example, `^(.+)_actual\.png$` maps `LoginScreen_actual.png` to the golden `LoginScreen.png`.

**Default:** `^(.+)_actual\.png$`

This regex is **not** part of golden-list matching; it only maps a selected golden to its generated
counterpart in *Test output* mode.

## Golden matching mode
> *Match goldens either by the name of annotated screenshot functions, or by a file/class regex.*

Two mutually exclusive radio buttons:

- **Match by annotated method** *(default)* — a golden matches when its path contains the name of an
  annotated / `test*` screenshot function.
- **Match by file/class regex** — you write regex patterns over the golden path using `{file_name}` and
  `{class_name}` placeholders.

The enabled field below the radios follows your choice. See [Matching goldens](matching.md) for a full
walkthrough and examples.

## Screenshot annotation name regex
> *Annotated functions whose annotation name matches become golden candidates; the golden must contain
> the function name.*

*(Enabled in **Match by annotated method** mode.)* Decides which functions count as screenshot
functions: a function is a candidate when its annotation name matches this regex, or its name starts
with `test`. `PreviewParameter` is always ignored. To recognize a custom annotation such as
`@ScreenshotTest`, add it, e.g. `.*Preview.*|Test|ScreenshotTest`.

**Default:** `.*Preview.*|Test`

## Golden filename regex (one per line)
> *Matched against each golden’s path. Examples: {file_name}, {class_name}, {class_name}\..\*,
> golden_{file_name}.*

*(Enabled in **Match by file/class regex** mode.)* One regex per line, matched anywhere in the golden’s
relative path. Placeholders: `{file_name}` = the current Kotlin file name (without `.kt`); `{class_name}`
= any class declared in the file. Values are regex-escaped before substitution.

**Default:** `{file_name}` and `{class_name}` (one per line).

## Excluded golden suffixes
> *Files ending with these suffixes are hidden from the golden list, for example Roborazzi _actual and
> _compare outputs.*

Comma-separated file-name suffixes (before the extension) to hide from the golden list, so generated
artifacts don’t appear as if they were goldens. Leave empty to exclude nothing.

**Default:** `_compare, _actual`

## Preview (live)
> *Runs the current (unsaved) settings against the golden directories and the Kotlin file open in the
> editor.*

A live panel that applies your **unsaved** settings against the golden directories and the Kotlin file
currently open, so you can see the effect as you type. It shows a count line such as
*“N match(es) for file …; classes …; methods …”* and lists the matched golden paths. If it can’t run, it
tells you why (for example *“Add a golden directory to preview matches.”* or *“Open a Kotlin file in the
editor to preview matches.”*).

![The live preview panel](images/settings-live-preview.png)
<!-- TODO(screenshot): the Preview (live) panel showing a match count and a list of matched golden paths -->

**Next:** [Matching goldens](matching.md) · [Troubleshooting & FAQ](troubleshooting.md)
