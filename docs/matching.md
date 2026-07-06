---
title: Matching goldens
nav_order: 4
---

# Matching goldens

This page explains how Golden Diff decides which goldens belong to the file you are editing, and how to
configure matching when the defaults don’t fit your project. For where each option lives, see the
[settings reference](settings-reference.md); if the list stays empty, see [troubleshooting](troubleshooting.md).

Golden Diff finds screenshots in two steps:

1. It reads the currently open Kotlin file and extracts names (class names, the file base name, and
   the names of annotated / `test*` functions).
2. It matches those names against the committed PNG goldens in the configured golden directories.

Matching uses one of **two mutually exclusive modes**, chosen with a radio button in
**Settings → Tools → Golden Diff → Golden matching mode**:

- **Match by annotated method** (default) — the golden filename contains the screenshot function name.
- **Match by file/class regex** — you write a regex over the golden path using `{file_name}` and
  `{class_name}`.

Matching is performed against each golden's path **relative to its golden directory** (with `/`
separators), not just the file name. Layouts that store the class or package as sub-directories (for
example `swift-snapshot-testing`, or Roborazzi's relative-path strategy) therefore still match.

## Mode: Match by annotated method

This is the default and needs no per-file configuration for most projects.

The **Screenshot annotation name regex** decides which functions are screenshot functions. A function
is a candidate when its annotation name matches the regex (default `.*Preview.*|Test`), or its name
starts with `test`. `PreviewParameter` is always ignored even though it contains `Preview`, because it
describes preview input data rather than a screenshot function.

A golden matches when its relative path **contains the function name** as a whole word or camel-case
segment (case-insensitive). Because it is a "contains" match, variant suffixes such as `_dark`, `.1`,
`_0`, or a parameter hash are matched automatically, while unrelated longer words are not (`Stat` does
not match inside `PlansScreenStatesPreview`).

This single rule covers the default naming of every major framework, because the method name is always
part of the golden name:

| Framework | Example golden | Function |
|---|---|---|
| Roborazzi (default) | `com.example.MyTest.emptyState.png` | `emptyState` |
| Roborazzi (relative path) | `com/example/MyTest.emptyState.png` | `emptyState` |
| Paparazzi | `com_example_ui_MyTest_emptyState.png` | `emptyState` |
| Compose Preview | `com.sample.tests.GreetingPreview_3d8b_0.png` | `GreetingPreview` |
| Shot / Dropshots | `com_example_MyTest_emptyState.png` | `emptyState` |
| swift-snapshot-testing | `MyVCTests/testEmpty.png` | `testEmpty` |

If your project uses a custom annotation, add it to the regex. For example, for `@ScreenshotTest`:

```text
.*Preview.*|Test|ScreenshotTest
```

Then `@ScreenshotTest fun emptyLoginState()` becomes a candidate and `…emptyLoginState….png` matches.

## Mode: Match by file/class regex

Use this when your screenshot functions are **not** annotated (so there is no method name to key off),
and goldens are named after the file or the class instead.

Add one regex per line in **Golden filename regex**. Each pattern is matched anywhere in the golden's
relative path (case-insensitive). Patterns support two placeholders:

```text
{file_name}  = the current Kotlin file name without .kt
{class_name} = any class declared in the current Kotlin file
```

Placeholder values are regex-escaped before substitution, so names such as `Login.Screen` are treated
as literal text. `{class_name}` expands to an alternation of the file's (useful) class names.

Because matching is "find anywhere", you usually do not need to add `.*` or `\.png`.

### Examples

File-based goldens (`LoginScreen.kt`):

```text
{file_name}_.*\.png
```

Matches `LoginScreen_empty.png`, `LoginScreen_dark.png`.

Class-based goldens (`class LoginScreenTest`):

```text
{class_name}_reference\.png
```

Matches `LoginScreenTest_reference.png`.

Class nested as a directory (e.g. `swift-snapshot-testing`):

```text
{class_name}
```

Matches `LoginScreenTests/testEmpty.png`.

Plain prefix without placeholders (unusual projects):

```text
auth_login_.*\.png
```

Matches `auth_login_empty_state.png`, `auth_login_dark_mode.png`.

## Generated output is separate

The matching above controls which committed golden files appear in the list.

The **Generated output filename regex** setting is different. It is used only after you select a golden
and choose **Compare: Test output**. Its job is to map the selected golden to a generated screenshot,
for example:

```text
^(.+)_actual\.png$
```

This maps `LoginScreen_actual.png` back to `LoginScreen.png`.

## Troubleshooting

If the list says **No screenshots found for this file**, check these in order:

1. The golden directory points at the folder that contains committed PNG goldens.
2. The matching mode matches your project (annotated method vs file/class regex).
3. In annotated-method mode: the annotation regex matches the annotations used by your screenshot
   tests, and the golden filenames actually contain the function name.
4. In file/class-regex mode: the patterns match your actual golden paths.
5. The excluded suffixes do not accidentally exclude real goldens.
6. The generated file regex is not involved in listing goldens; it only maps selected goldens to test
   output.
