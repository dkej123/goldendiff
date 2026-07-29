# Prompt for the Design Agent — Golden Diff UI improvements

You are designing the following improvements to Golden Diff. Use the product's established design
system and your existing knowledge of the application.

Focus only on the UI, interaction model, hierarchy, and required states. Do not define technical
implementation details.

## 1. Show only snapshot-related files

The project panel currently shows every file in the project, which creates unnecessary noise.

Change it so that it shows only source or test files associated with at least one existing snapshot.
A brand-new snapshot test with no snapshot yet does not need to appear in this iteration.

The panel should support:

- `Group by folders`, enabled by default;
- a flat list when folder grouping is disabled;
- failed files first in the flat list, followed by alphabetical sorting by path;
- a `Failed only` filter;
- text search that works together with grouping and `Failed only`;
- keeping parent folders visible in filtered results so users retain path context.

A file is considered failed when at least one of its detected Test output snapshots is new or differs
from Git HEAD. Communicate this state clearly on the file. When files are grouped, folders should
aggregate the state and communicate how many failing files they contain.

Include the relevant loading, empty, no-failures, indexing, and filtered-empty states.

## 2. Detect Working copy and Test output automatically

The current global choice between Working copy and Test output is difficult to understand. Remove the
need to choose one source before seeing the changes.

Golden Diff should detect and show both sources automatically in:

- Current file;
- Project changes.

The same golden must appear only once, even when it has both a Working copy change and a Test output
change.

Communicate the independent states:

- `Failed` — Test output is new or differs from Git HEAD;
- `Modified` — Working copy differs from Git HEAD;
- `New` — a new Working copy golden is not present in Git HEAD.

If a golden has both Working copy and Test output, let the user switch between them locally in the
comparison area. Default to Test output because it is the actionable failure. Do not show a redundant
switch when only one source is available.

Project changes should combine changes from both sources and order them as follows:

1. Failed;
2. Modified;
3. New;
4. path.

Cover single-source, dual-source, and missing-counterpart states.

## 3. Accept all Test outputs for the current file

Add an `Accept all (N)` action near the list of snapshots associated with the currently open
source/test file.

The action:

- accepts all differing Test output snapshots associated with the current file;
- is independent of the currently selected snapshot and visual list filtering;
- is not a project-wide Accept all;
- should not appear as a global action in Project changes;
- requires confirmation because it overwrites multiple goldens.

The confirmation should identify the current file and the number of snapshots that will be accepted.

Cover:

- available and unavailable states;
- confirmation;
- progress;
- full success;
- partial failure;
- nothing left to accept.

After a partial failure, users should understand which snapshots still need attention without losing
their current context.

## 4. Add Settings to the macOS application menu

Add `Settings…` with the standard `⌘,` shortcut to the native Golden Diff application menu. It opens
the existing Settings window.

The menu entry should remain discoverable when no project is open. Project-dependent settings can
explain that a project must be opened.

## 5. Notify users when Generate or Verify finishes

Add the following option to the Commands settings in both the standalone application and the
IntelliJ/Android Studio plugin:

`Notify when tasks finish`

The option is enabled by default.

Notifications should:

- cover Generate and Verify;
- cover whole-project and current-file tasks;
- appear after success or failure;
- not appear after manual cancellation;
- appear only when the corresponding application or IDE is not in the foreground;
- identify the task, scope, project or file, and outcome;
- open the relevant command log when clicked.

The standalone application uses a system notification. The plugin uses the IDE's native notification.

Cover:

- the setting enabled and disabled;
- system notifications being blocked;
- a clear route to the relevant system settings when blocked;
- success and failure notifications.

## Scope boundaries

- The file-list, filtering, source-detection, comparison, and Accept all changes apply only to the
  standalone application.
- Task-completion notifications apply to both the standalone application and the IDE plugin.
- Do not redesign the plugin's snapshot list or comparison area.
- Do not design detection of new snapshot tests that do not have an existing snapshot.
- Do not derive per-file failure state from command logs or test reports.
- Do not add a dedicated Xcode `test-without-building` workflow.
