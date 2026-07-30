# Golden Diff IntelliJ plugin redesign — Design Agent brief

## Purpose of this brief

Design the next workflow for the public **Golden Diff** plugin for IntelliJ IDEA and Android Studio.
The redesign brings the standalone application's snapshot-aware workflow into the IDE tool window
without losing the editor, Run tool window, VFS, and extension integrations that make the plugin
useful inside a JetBrains IDE.

This is a product and interaction brief. Define the information architecture, behavior, hierarchy,
states, microcopy, focus model, and responsive behavior. Do not produce a Kotlin implementation
plan. You know the JetBrains Design System: choose the appropriate native components, patterns,
spacing, icons, and visual details rather than waiting for this brief to prescribe them.

## Product context

Golden Diff helps developers find screenshot-test goldens and compare Git HEAD with either the
working-copy golden or generated test output. It is tool-agnostic: the same workflow must make sense
for Roborazzi, Paparazzi, Compose Preview Screenshot Testing, Shot, Swift snapshot testing, and other
systems that produce image goldens.

### Current public plugin

The current plugin is a right-anchored Tool Window. It has:

- a toolbar;
- global **Scope** and **Compare** choices;
- a wrapping grid of goldens on the left;
- one comparison area on the right;
- automatic refresh when the active editor tab changes.

The current scopes are **Current file** and **Project changes**. The current global Compare choice
switches between **Working copy** and **Test output** before the user sees the combined state.

### Newer standalone workflow

The standalone application has a more snapshot-aware workflow:

- **Files** navigation;
- a **Goldens** collection for the current selection;
- one active **Compare** area;
- navigation through **Folder → View → Snapshot case**;
- Working copy and Test output detected and merged automatically.

A **View** is the source-facing parent that the developer recognizes. A **Snapshot case** is a
specific test method, preview, story, or equivalent generator associated with one or more goldens.
Some goldens have only View-level evidence and therefore remain directly associated with the View.

The plugin redesign should adopt this workflow, adapted to the constraints and strengths of an IDE
Tool Window. It does not need to copy the standalone Compose UI one-to-one.

### Settled target navigation

The redesigned workflow has no global **Scope** or **Compare** selector:

- **Files** is one project-wide snapshot navigation surface. Editor-follow selects a View within it;
  it does not switch the Tool Window into a separate Current file scope.
- Failed, Modified, and New filters provide the actionable project-changes view without a separate
  Project changes scope.
- Selecting a View or Case determines the Goldens content.
- Working copy and Test output are merged and the comparison source is selected automatically from
  their statuses. There is no global or per-golden manual source selector.

The design must provide enough context around the selected View/Case that removing the two global
selectors does not make command scope or comparison source ambiguous.

## Design objective

Make Golden Diff a compact, native IDE workspace in which a developer can:

1. arrive automatically from the active editor tab or start from the plugin's Files search;
2. understand which Views and cases are failing or changed;
3. inspect each golden exactly once even when it has multiple sources;
4. compare HEAD with the relevant source without a global source-mode decision;
5. run Generate or Verify at the appropriate scope;
6. accept one or all actionable Test outputs;
7. retain context while the IDE, filesystem, or commands refresh the data.

The result must work in wide, medium, and narrow Tool Window widths, in both light and dark IDE
themes.

## Required main Tool Window structure

Design one coherent Tool Window containing the following product surfaces.

### Toolbar and commands

Provide access to:

- Generate for the whole project;
- Verify for the whole project;
- Generate for the selected View;
- Verify for the selected View;
- Verify for the selected Snapshot case;
- Stop while a command or batch is running;
- settings/directory configuration;
- optional Figma mode when contributed by the internal extension.

The design must communicate unavailable commands, their required configuration, current scope,
running scope, and command result without relying only on transient IDE notifications.

### Files

Files is the primary snapshot-aware navigation surface. It contains:

- Folder → View → Snapshot case hierarchy;
- text search;
- Failed, Modified, and New status filters;
- folder-level counters;
- selection, expansion, and editor-opening actions;
- indexing, empty, filtered, and editor-follow feedback.

Only files associated with at least one existing golden are in scope. Do not design discovery of
snapshot tests that do not yet have a golden.

### Goldens

Goldens shows the images associated with the selected View or Snapshot case. It must:

- show each golden exactly once;
- expose independent Working copy and Test output status;
- support a single selected golden;
- provide single **Accept** and View-scoped **Accept all** where applicable;
- preserve meaningful selection and scroll context during background updates;
- distinguish Failed, Modified, New, and Unchanged.

### Compare

Compare displays Git HEAD against the selected golden source and retains the existing comparison
capabilities:

- Side by side;
- Swipe;
- Onion skin;
- Pixel diff;
- zoom;
- unchanged/single-image presentation;
- loading, missing-counterpart, and error states.

Compare selects the source automatically:

- a changed or new Test output (**Failed**) is compared with HEAD;
- otherwise Working copy is compared with HEAD;
- if the preferred variant does not exist, show the only available built-in variant.

Do not show a global or local **Test / Working** selector. The Compare header, labels, status, and
Accept availability must make the automatically selected source unambiguous. After Accept consumes a
failing Test output, Compare automatically returns to Working copy.

### Status and feedback

Provide persistent or contextual feedback for:

- indexing and background refresh;
- command progress and cancellation;
- success, failure, and partial failure;
- Accept and Accept all progress/results;
- comparison loading/errors;
- configuration and empty states.

Feedback must not unexpectedly replace the user's current selection or hide unrelated navigation.

## Responsive Tool Window behavior

Design explicit layouts for three width classes:

- **Wide:** Files, Goldens, and Compare can be visible together with useful working widths.
- **Medium:** preserve the workflow and selected context while reducing or collapsing secondary
  surfaces according to a clear priority.
- **Narrow:** provide a deliberate navigation model rather than compressed, unusable panes. The user
  must be able to move between Files, Goldens, and Compare and understand where they are.

For every width class, specify:

- which surfaces are visible;
- pane order and hierarchy;
- how the user moves between surfaces;
- splitter behavior and minimum practical sizes;
- which panes may collapse and how they are restored;
- what happens to toolbar commands and status feedback;
- whether resizing restores previous pane proportions;
- how selection, scroll position, zoom, and comparison mode survive layout changes.

Do not assume a fixed right-anchored width. JetBrains Tool Windows may be docked, undocked, resized,
or moved.

## Files navigation behavior

### Folder → View → Snapshot case

- Folder nodes aggregate Views and start in a sensible collapsed or expanded state defined by the
  design.
- View selection shows all goldens belonging to that View, including View-level goldens.
- Case selection shows only the goldens associated with that case.
- A View may have no Case children when matching evidence is only View-level.
- Expanding or collapsing a folder or View must not run a command or open an editor tab.
- Selecting a golden may reveal and highlight its most specific owning Case, or its View when no
  Case association exists.
- Define behavior for selected nodes that disappear after a structural refresh.

### Search

Files search must match:

- View name;
- normalized project-relative View path;
- normalized project-relative generator path;
- Snapshot method/case name.

Paths use `/` as the conceptual separator in search and presentation regardless of host platform.
Do not expose an absolute local filesystem path as the primary searchable or displayed identity.

Search works together with status filters. Matching descendants keep enough ancestors visible to
preserve path context. Define:

- how matches are emphasized;
- whether matching branches expand temporarily;
- what happens to the user's prior expansion state when search is cleared;
- keyboard result navigation;
- the difference between no project snapshots and no search results.

Search must support starting from the plugin: entering `A`, choosing the appropriate View, and
immediately navigating to its snapshots.

### Status filters

Provide combinable **Failed**, **Modified**, and **New** filters.

- No active status filter shows all relevant Views and cases.
- Multiple active filters use OR semantics.
- Active filters combine with the text query.
- Selecting all three still excludes Unchanged-only entries.
- Folder counters count **failing Views**, not failing cases, methods, or goldens.
- A failed unmatched golden remains represented on its View.

Define clear empty states for a query with no matches and for active status filters with no matches,
including an action to clear the relevant constraint.

## Status semantics

Use these product meanings consistently:

- **Failed:** Test output is new or differs from Git HEAD.
- **Modified:** an existing Working copy golden differs from Git HEAD.
- **New:** a Working copy golden is not present in Git HEAD.
- **Unchanged:** the available compared content is identical to HEAD and no higher-priority state
  applies.

A merged golden may simultaneously have a Failed Test output and a Modified or New Working copy.
The UI must retain both facts even if it uses a dominant status for sorting or compact display.

Views and cases aggregate the statuses of their associated goldens. Folder counters report how many
Views contain at least one Failed Test output.

## Merged golden behavior

- One golden path appears once in Goldens.
- Working copy and Test output remain independent variants with independent status.
- The comparison source is derived from merged status and is not manually selectable.
- A changed or new Test output wins because it is the actionable **Failed** variant.
- Modified, New, and Unchanged items use Working copy when it exists.
- If the preferred source is absent, the only available built-in variant is used.
- When a Test output disappears after Accept or refresh, the comparison automatically returns to
  Working copy without clearing the golden selection.
- A missing generated counterpart is absence of Test output, not an Unchanged Test output.
- More than one equally valid generated counterpart is an **ambiguous Test output**, not an arbitrary
  first match. It blocks comparison and Accept until the configuration or files make the mapping
  unambiguous.
- Sorting should make actionable Failed items easiest to find, followed by Modified, New, then
  Unchanged/path, while still showing the secondary status of dual-source items.

Define cards/rows, selection, hover, status communication, source communication, and missing-source
feedback for single- and dual-variant goldens. Also define the error presentation and recovery action
for an ambiguous Test output.

## Accept behavior

### Single Accept

Single **Accept** is available only for an actionable Test output. It promotes the generated image to
the golden. It must accept the exact Test output currently displayed in Compare; it must never
silently resolve a different same-named file when the action is invoked. Design:

- enabled, unavailable, accepting, accepted, and failed states;
- stale-source feedback when the displayed Test output changes or disappears before the write;
- blocked feedback for an ambiguous Test output;
- feedback when the item changes or disappears after acceptance;
- preservation of the current View/Case and the nearest useful golden selection.

### Accept all for the selected View

**Accept all (N)** accepts every differing Test output associated with the selected View. It is:

- View-scoped, never project-wide;
- independent of the selected golden, selected Case, text search, and visible status filters;
- absent or explicitly unavailable when no View context exists;
- destructive and requires confirmation.

Design the full flow:

- confirmation naming the View and count;
- progress such as `Accepting X of N…`;
- full success;
- partial failure with per-item clarity;
- retry of unresolved items only;
- a stale-action `Nothing to accept` result;
- preservation of selection, scroll, and successfully accepted items while failed items stay
  actionable.

## Generate and Verify commands

The commands are user-configured shell strings. Golden Diff does not assume a particular snapshot
framework.

Design entry points for:

- Generate whole project;
- Verify whole project;
- Generate selected View;
- Verify selected View;
- Verify selected Case.

Verify View may execute a deduplicated sequential batch when a View has multiple generator files.
Verify Case may fall back to the generator's Verify View command if no case command is configured;
the UI must state that the broader generator/View scope will run.

All commands and batches use a single IntelliJ Run output. Design:

- configured and unconfigured actions;
- pre-run scope clarity;
- running and queue progress;
- Stop behavior;
- successful completion;
- failed completion;
- partial failure for a batch;
- manual cancellation as a distinct, non-failure result;
- retry/re-run affordances where appropriate;
- refresh feedback after completion.

Cancellation must not be presented as a failure and does not produce a completion notification.

## Editor integration

Editor-follow is a primary use case, not an optional convenience.

### Follow the active tab

- Opening editor tab `A` automatically finds the corresponding View, selects it in Files, and shows
  its goldens.
- Changing the active tab drives synchronization.
- Moving the caret or clicking within the same file must not rebuild navigation, reset the selected
  golden, or reset scroll position.
- When the active file has no associated snapshots, show a clear empty state for that file without
  deleting or replacing the entire Files tree.
- If the current search query or status filters hide the active file, preserve the user's query and
  filters. Show an explanation and an action such as **Reveal A** rather than clearing constraints
  automatically.
- Define whether Reveal temporarily overrides constraints, clears only the relevant constraints, or
  offers a choice; make the result predictable and reversible.

### Open in editor

Design native IDE actions to:

- open the selected View in the editor;
- open the generator associated with a Case;
- navigate to the specific snapshot method when a Case is selected.

Specify discoverability through mouse, keyboard, context actions, double-click/Enter behavior, and
focus return. Use standard IDE navigation expectations; do not invent a standalone project-opening
workflow.

## Optional Figma mode

The internal **Golden Diff — Figma** plugin contributes an optional comparison source through the
public plugin's extension point. The public redesign must work when that extension is absent and
expose a coherent Figma mode when it is present.

Design:

- where Figma mode is selected;
- loading, error, and ready states;
- how the user returns to the built-in snapshot workflow;
- how selection and comparison context are retained;
- how Figma avoids being confused with the merged Working copy/Test output model.

Do not make the public plugin depend on Figma-specific UI being present.

## Required state matrix

The handoff must explicitly cover each state below, either as a main composition, a state-gallery
entry, or a documented variant linked to an exact component.

| Area | Required states |
|---|---|
| Setup and indexing | No configuration; initial indexing; background refresh |
| Files content | No snapshot-related files; no search results; no active-filter results; active editor file hidden by search and/or filters |
| Tree | Folder collapsed/expanded; View selected/unselected; Case selected/unselected |
| Status | Failed; Modified; New; Unchanged; dual/combined statuses |
| Golden sources | Working-copy-only; Test-output-only; both variants with Failed Test output selected automatically; both variants with Working copy selected automatically; post-Accept fallback to Working copy; generated counterpart absent; ambiguous Test output |
| Comparison | Identical to HEAD; changed; new/no HEAD; loading; decoding/load error; accepted |
| Single Accept | Available; unavailable; accepting; accepted; failed; stale source; blocked by ambiguity |
| Accept all | Confirm; progress; success; partial failure; retry; nothing to accept |
| Commands | Running; success; failure; partial failure; cancelled; unconfigured |
| Figma | Extension absent; loading; error; ready |
| Appearance | Light theme; dark theme |

Also show how long names, deep paths, many folder results, many goldens, and a very small Tool Window
degrade gracefully.

## Scope boundaries

The redesign applies to the public IntelliJ/Android Studio plugin.

Preserve:

- active-editor tab following;
- command output in IntelliJ's Run tool window;
- VFS-driven image refresh;
- the optional `ExtraComparisonSource`/Figma extension;
- Side by side, Swipe, Onion skin, Pixel diff, and zoom;
- compatibility with standard IDE keyboard and accessibility behavior.

Do not design:

- a custom project-opening window or project picker;
- standalone macOS application menus;
- macOS Notification Center behavior;
- standalone onboarding;
- discovery of snapshot tests without an existing golden;
- status logic derived from test reports or command output;
- a redesign of the complete Settings window;
- a literal one-to-one copy of the standalone Compose appearance.

Do not prescribe Kotlin classes, Swing layout managers, background executors, caches, or persistence
details. The implementation agent will handle those after design approval.

Settings is in scope only where the Tool Window links to existing settings, a command or directory is
unconfigured, Verify Case fallback must be explained, or the redesigned workflow requires exact
microcopy for an existing/new field. Use the existing native Settings structure; do not design a new
Settings information architecture.

## Required handoff

Deliver:

- the main Tool Window at wide, medium, and narrow widths;
- light and dark variants;
- a state gallery covering the complete matrix above;
- interaction notes for mouse, keyboard, resizing, drag/split, collapse, refresh, and editor-follow;
- a keyboard and focus-navigation specification, including focus order and restoration;
- splitter and pane-collapse behavior;
- exact user-facing microcopy for actions, empty states, errors, progress, confirmation, partial
  failure, cancellation, and Reveal;
- component definitions and every required variant;
- a mapping of every view and state to **Files**, **Goldens**, **Compare**, **Commands**, or
  **Settings**;
- all icons/assets needed by implementation, with light/dark and scale variants where applicable;
- an inventory of every delivered artifact with its exact path and canonical state/frame name.

Include a short decision log for ambiguous interactions and a list of any requirements that could not
be represented. Do not leave visual or behavioral decisions for the implementation agent to invent.

## Handoff acceptance checklist

The handoff is ready for implementation only when reviewers can check all of the following:

- [ ] Wide, medium, and narrow layouts are present.
- [ ] Light and dark variants are present.
- [ ] Files, Goldens, Compare, Commands, and Settings are mapped.
- [ ] The complete required state matrix is mapped to named artifacts.
- [ ] Editor tab `A` → selected View `A` → visible goldens is demonstrated.
- [ ] Same-file caret movement preserves golden selection and scroll.
- [ ] Hidden active file behavior includes explicit Reveal microcopy.
- [ ] Search by View name/path, generator path, and Case is demonstrated.
- [ ] Failed, Modified, and New filters and their OR combination are demonstrated.
- [ ] Single- and dual-source automatic selection states, including post-Accept fallback, are
      demonstrated without a manual source selector.
- [ ] Missing and ambiguous Test output states are demonstrated.
- [ ] Single Accept, stale-source protection, and every Accept all state are demonstrated.
- [ ] Verify View, Verify Case fallback, batch, Stop, partial failure, and cancellation are covered.
- [ ] Figma absent/loading/error/ready behavior is covered.
- [ ] Keyboard, focus, splitters, collapse, and accessibility notes are complete.
- [ ] Exact microcopy and artifact paths are included.
