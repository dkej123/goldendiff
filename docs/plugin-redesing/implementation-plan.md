# Golden Diff IntelliJ plugin redesign — implementation plan

Status: **implementation in progress — automated verification passing**

This is the living execution plan for the agent implementing the public IntelliJ/Android Studio
plugin redesign described in [`design-agent-brief.md`](design-agent-brief.md). The implementation
agent owns this document and must update it continuously. The Design Agent does not maintain it.

No Swing UI implementation starts until Phase 0 is complete and the design handoff is reviewed.

## Progress rules

- Every task starts as `[ ]`.
- Change a task to `[x]` only after it is implemented and proportionately verified.
- Partially completed work remains `[ ]` and gets a short note describing what is done and what is
  still missing.
- After every phase, record the exact commands and manual checks run, their date, and their result.
- Read Gradle output and record `BUILD SUCCESSFUL` or `BUILD FAILED`; never infer success from a
  piped command's exit code.
- Record blockers and every deliberate deviation from the approved design under the affected phase,
  with rationale and reviewer decision.
- Do not close a phase merely because code was written. Its automated and required manual checks must
  also be complete.
- Before final handoff, reconcile every checkbox with the actual repository state.
- Preserve unrelated user changes in the worktree.
- Commit messages and release notes must not mention AI tools or assistants.

## Fixed architecture and scope constraints

- `:core` remains host-neutral JVM logic and must not depend on IntelliJ, Swing, Compose, or
  `:core-ui`.
- `:public-plugin` remains Swing-based, packages `core.jar`, targets build 241+, and emits Java 17
  bytecode.
- `:internal-plugin` remains a dependent Figma extension, uses `:core` as `compileOnly`, and must not
  package a second `core.jar`.
- Neither plugin may depend on `:core-ui` or package Compose, Jewel, or Skiko.
- IntelliJ command execution remains in `:public-plugin` and streams to the Run tool window.
- Editor-follow is driven by active file/tab changes, never caret movement.
- The redesigned Tool Window has no global Scope or Compare selector. Files is project-wide; status
  filters expose actionable changes, and the comparison source is derived automatically from merged
  status. There is no per-golden manual source selector.
- Existing standalone behavior must remain working as reusable models move into `:core`.
- Do not add Kotlin Analysis API calls; PSI-only behavior preserves K2 compatibility.

## Baseline at plan creation

The redesign planning baseline contains substantial standalone and `:core` snapshot-workflow work
that must be preserved and reused where host-neutral:

- `ProjectFileIndex`, `SnapshotFileIndex`, merged golden scanning, automatic golden-source
  resolution,
  command planning, and bulk accept behavior already exist for the standalone workflow;
- the public plugin already has partial completion-notification support;
- the recorded baseline checks pass:
  - `./gradlew :core:test`;
  - `./gradlew :app:test`;
  - `./gradlew :public-plugin:test`;
  - `./gradlew :internal-plugin:test`;
  - `./gradlew :public-plugin:buildPlugin`;
  - `./gradlew :internal-plugin:buildPlugin`;
- the recorded public ZIP contains `core.jar`;
- the recorded plugin ZIP audit finds no Compose, Jewel, or Skiko payload.

Re-run and record this baseline before implementation. Do not treat this summary as current test
evidence.

Known plugin gaps and risks at the start:

- snapshot placeholders are exposed in plugin command configuration but are not fully expanded;
- manual Stop is currently surfaced as a failure notification in some paths;
- the plugin has no Files/View/Case tree;
- the plugin has no merged Working copy/Test output items or automatic status-driven source
  resolution;
- Verify Case and View-scoped Accept all are absent;
- generated-output changes may trigger repeated full scans of generated roots.

### Baseline verification

- [x] Record `git status --short` and preserve unrelated changes.
- [x] Run `./gradlew :core:test :app:test :public-plugin:test :internal-plugin:test`.
- [x] Run `./gradlew :public-plugin:buildPlugin :internal-plugin:buildPlugin`.
- [x] Confirm every Gradle invocation reports `BUILD SUCCESSFUL`.
- [x] Inspect both ZIPs and record the exact artifact paths.
- [x] Confirm `core.jar` exists only in the public ZIP.
- [x] Confirm neither ZIP contains Compose, Jewel, or Skiko.

Commands/checks run:

- 2026-07-30: `git status --short` recorded. Existing `app/**`, `docs/architecture.md`, and
  `docs/features.md` edits were identified as user work and preserved.
- 2026-07-30: `./gradlew :core:test :app:test :public-plugin:test :internal-plugin:test` —
  `BUILD SUCCESSFUL`.
- 2026-07-30: `./gradlew :public-plugin:buildPlugin :internal-plugin:buildPlugin` —
  `BUILD SUCCESSFUL`.
- Public artifact: `public-plugin/build/distributions/golden-diff-1.5.0-beta.13-dev.zip`.
- Internal artifact: `internal-plugin/build/distributions/golden-diff-figma-1.3.2.zip`.
- ZIP audit: `core.jar` present only in the public artifact; no Compose/Jewel/Skiko entry in either.

Baseline deviations/blockers:

- _None recorded._

---

## Phase 0 — receive and approve the design handoff

This phase is a hard gate. Do not start Phase 3 Swing UI work before every unresolved design issue is
closed. Core investigation or tests may proceed only if they do not pre-empt a visual/interaction
decision.

- [x] Complete and review [`design-agent-brief.md`](design-agent-brief.md).
- [x] Give the brief to the Design Agent without adding Kotlin/Swing implementation constraints that
      contradict it.
- [x] Inventory every delivered artifact and record its exact path below.
- [x] Identify canonical wide, medium, narrow, light, dark, and state-gallery references.
- [x] Record component definitions, variants, assets, icons, and exact microcopy.
- [x] Review keyboard/focus behavior, splitter behavior, pane collapse, resize restoration, and
      accessibility notes.
- [x] Verify that every required state in the brief is represented by a named artifact or explicit
      interaction note.
- [x] Verify editor tab `A` → View `A` selection and the hidden-active-file Reveal flow.
- [x] Verify search, combined filters, merged sources, Accept all, command batch/cancel, and Figma
      flows.
- [x] Resolve contradictions between the handoff, product requirements, JetBrains conventions, and
      platform constraints before UI implementation.
- [x] Fill the `Design state → implementation surface` table with exact handoff names and paths.
- [x] Obtain reviewer approval for the handoff and record the date/decision.

### Design artifact inventory

| Artifact | Exact path | Canonical frames/states | Notes |
|---|---|---|---|
| Handoff README | `/Users/dkwasniak/Downloads/design_handoff_golden_diff_plugin_redesign 3/README.md` | Wide/Medium/Narrow, light/dark, components, interactions, tokens | Latest canonical written specification; supersedes revisions 1 and 2 |
| Interactive reference | `/Users/dkwasniak/Downloads/design_handoff_golden_diff_plugin_redesign 3/Golden Diff Plugin v2.dc.html` | Main, States, Figma on/off, Reveal demo, Files top/bottom | Latest canonical visual/state reference; custom design-canvas HTML, not production web code |

### Design state → implementation surface

| Required design state | Artifact/frame | Product area | Planned implementation surface | Status |
|---|---|---|---|---|
| Wide main window | HTML Main / Wide | Shell | `RedesignedScreenshotPanel` | Implemented; manual fidelity check pending |
| Medium main window | HTML Main / Medium | Shell | `RedesignedScreenshotPanel` | Implemented; manual fidelity check pending |
| Narrow main window | HTML Main / Narrow | Shell | `RedesignedScreenshotPanel` | Implemented; manual fidelity check pending |
| Files hierarchy/search/filters | HTML Files pane | Files | `SnapshotFilesPanel`, shared projection | Implemented |
| Goldens single/dual variants | HTML Goldens pane | Goldens | `MergedGoldenCellRenderer` | Implemented |
| Compare modes/states | HTML Compare pane | Compare | `CompareView` and canvases | Implemented |
| Commands and command results | HTML commands/log | Commands | `BatchCommandExecution`, IntelliJ Run | Implemented; notification/manual checks pending |
| Accept/Accept all | HTML Goldens/Accept modals | Goldens/Commands | resolved-source `GoldenAccept` + panel | Implemented; retry dialog refinement pending |
| Editor follow/Reveal/open | HTML Files interactions | Files | controller/editor bridge | Implemented |
| Figma absent/loading/error/ready | HTML Figma modes | Compare | `ExtraComparisonSource` host surface | Implemented |
| Settings/unconfigured actions | README mapping / States | Settings | `ScreenshotConfigurable`, panel links | Implemented |
| Full state gallery | HTML States | All | typed controller/Compare states | Implemented subset; manual audit pending |

### Required-state audit

- [ ] No configuration.
- [ ] Initial indexing.
- [ ] Background refresh.
- [ ] No snapshot-related files.
- [ ] No search results.
- [ ] No active-filter results.
- [ ] Active editor file hidden by search query and/or filters.
- [ ] Folder collapsed and expanded.
- [ ] View selected and unselected.
- [ ] Case selected and unselected.
- [ ] Failed, Modified, New, Unchanged, and combined status.
- [ ] Working-copy-only, Test-output-only, dual variant, no generated counterpart, and ambiguous Test
      output.
- [ ] Identical to HEAD, changed, new/no HEAD, accepted, comparison loading, and comparison error.
- [ ] Single Accept available, unavailable, accepting, accepted, failed, stale-source, and
      ambiguity-blocked states.
- [ ] Accept all confirm, progress, success, partial failure, retry, and nothing to accept.
- [ ] Command running, success, failure, partial failure, cancelled, and unconfigured.
- [ ] Figma absent, loading, error, and ready.
- [ ] Light and dark themes.

### Phase 0 verification

- [x] Review the complete mapping against every delivered artifact.
- [x] Confirm no UI decision needed for implementation remains unspecified.
- [x] Record approval and all resolved discrepancies.

Commands/checks run:

- 2026-07-30: reviewed README completely and inventoried the 1145-line HTML reference.
- 2026-07-30: user supplied the handoff and explicitly requested implementation; treated as reviewer
  approval for the handoff and its documented decisions.
- 2026-07-30: user supplied revision `design_handoff_golden_diff_plugin_redesign 2`; its Goldens-card,
  density-control, Accept-action, and two-row Compare-header deltas supersede the first delivery.
- 2026-07-30: user supplied revision `design_handoff_golden_diff_plugin_redesign 3`; its full-height
  Goldens column, Files/Compare 50/50 start, simplified header, and compact flat zoom controls
  supersede revision 2.

Design decisions/deviations/blockers:

- The HTML uses a custom design-canvas template syntax and does not render as a standalone browser
  page. README, state definitions, tokens, and HTML source remain the canonical review material.
- `JAVA_HOME` remains standalone-only by reviewer decision; the plugin keeps the same five command
  templates but runs them through the IDE/platform shell.

---

## Phase 1 — shared logic in `:core`

Reuse the standalone models where they already satisfy the contract. Refactor or extend them rather
than copying equivalent logic into the plugin.

### Tree projection and filtering

- [x] Define or extract a host-neutral Folder → View → Snapshot case projection model.
- [x] Support stable View and Case identifiers independent of Swing row instances.
- [x] Support search by View name, normalized project-relative View path, normalized project-relative
      generator path, and case/method name.
- [x] Combine Failed, Modified, and New filters using OR semantics.
- [x] Keep ancestors of matching descendants for path context.
- [x] Preserve View-level unmatched goldens without inventing a Case.
- [x] Count failing Views once per folder, independent of failing case/golden count.
- [x] Define deterministic ordering for folders, Views, cases, and status precedence.
- [x] Add pure tests for projection, search, filter combinations, counters, and stable ordering.

### Merged and automatic source model

- [x] Review `MergedGoldenItem` as the common one-golden/two-variant contract and close plugin needs
      without host dependencies.
- [x] Extract or reuse a host-neutral automatic source rule: a changed/new Test output wins;
      otherwise Working copy wins; if the preferred variant is absent, use the only available
      built-in variant.
- [x] Ensure a dual-source item with an unchanged Test output and Modified/New Working copy selects
      Working copy.
- [x] Ensure Accept or refresh automatically returns comparison to Working copy when the failing Test
      output disappears, without clearing golden selection.
- [x] Do not store a session-local source preference and do not expose a manual source-selection
      transition.
- [x] Preserve independent Working copy and Test output status.
- [x] Treat missing generated counterpart as an absent Test output variant.
- [x] Add tests for changed/new Test output, Modified/New Working copy with unchanged Test output,
      unchanged dual source, single-source fallback, post-Accept fallback, and secondary status
      retention.

### View/Case command planning

- [ ] Extract or complete host-neutral planning inputs for Generate/Verify View and Verify Case.
- [ ] Deduplicate generators and identical expanded commands for View batches.
- [ ] Preserve stable sequential command order.
- [ ] Define Verify Case fallback to the generator/View command with typed scope information.
- [ ] Define typed batch outcomes: success, failure, partial failure, and cancellation.
- [ ] Add tests for one/many generators, duplicate expansion, fallback, empty configuration,
      cancellation, and aggregate outcomes.

### Generated counterpart indexing and matching

- [x] Build or expose a generated-counterpart index that scans each configured generated root once
      per relevant refresh.
- [x] Reuse the same index for status scanning and comparison resolution; Accept carries the resolved identity.
- [x] Preserve same-relative-directory preference before fallback matching.
- [x] Return a typed ambiguous result when basename/path ranking cannot identify exactly one generated
      counterpart; never choose an arbitrary first match.
- [x] Carry the resolved golden → generated-file identity from comparison/controller state into
      single Accept and Accept all instead of resolving it again at action time.
- [x] Detect a stale resolved source before writing when it changed, moved, or disappeared after the
      comparison was loaded.
- [x] Ensure one changed generated file does not cause one full generated-root scan per golden.
- [x] Add tests for root scan count, same-directory preference, fallback, collision, duplicate roots,
      stale resolved sources, and configuration invalidation.

### Accept contracts

- [x] Review/extend `GoldenAccept` so single Accept returns an unambiguous typed outcome.
- [x] Change Accept inputs to use the exact resolved generated source displayed to the user, with
      content identity/staleness validation appropriate to the shared model.
- [x] Ensure bulk Accept returns success/failure for every candidate and can identify the unresolved
      retry subset.
- [x] Never delete generated output unless golden bytes were successfully written.
- [x] Make stale/no-op `Nothing to accept` distinguishable from success.
- [x] Add tests for full success, missing/ambiguous counterpart, read/write failure, partial failure,
      cleanup, retry subset, and nothing to accept.

### Phase 1 verification

- [x] Run `./gradlew :core:test`.
- [ ] Confirm the Gradle log reports `BUILD SUCCESSFUL`.
- [ ] Confirm `:core` has no IntelliJ, Swing, Compose, or `:core-ui` dependency.

Commands/checks run:

- 2026-07-30: `./gradlew :core:test :app:test` — `BUILD SUCCESSFUL`.
- Added `SnapshotTreeProjectionTest`, `AutomaticBuiltInSourceTest`,
  `GeneratedCounterpartIndexTest`, ChangeScanner ambiguity coverage, and
  `GoldenAcceptResolvedTest`.

Design deviations/blockers:

- _None recorded._

---

## Phase 2 — plugin snapshot controller

Build a testable controller/state layer independent of Swing rendering. UI components observe typed
state and dispatch intents; they do not own scanning, command planning, or editor synchronization.

### Indexing and lifecycle

- [x] Build `ProjectFileIndex` off the EDT.
- [x] Build/refresh `SnapshotFileIndex` off the EDT.
- [x] Add generation/request guards so stale asynchronous results cannot replace newer project,
      configuration, selection, or refresh state.
- [x] Define controller lifecycle and cancellation under IntelliJ `Disposable`.
- [x] Separate initial indexing, background refresh, ready, empty, and error states.
- [x] Preserve the previous usable snapshot during soft refresh.

### Selection and navigation state

- [x] Model Folder expansion, View selection, Case selection, and golden selection independently of
      Swing components.
- [x] Preserve selection through equivalent index replacements using stable IDs.
- [x] Define deterministic fallback when a selected View, Case, golden, or variant disappears.
- [x] Derive the selected comparison source from current merged status on every relevant state
      transition; keep no remembered source preference.
- [x] Expose scroll/reveal intents without storing Swing widget instances in the controller.

### Editor synchronization

- [x] Subscribe to editor file/tab selection changes.
- [x] Do not add a caret listener.
- [x] Map the active editor file to its View and select/reveal that View.
- [x] Avoid resetting golden selection or scroll when the active file has not changed.
- [x] Represent an active file with no snapshots without clearing the Files index.
- [x] Represent an active file hidden by query/filters and expose a typed Reveal action.
- [x] Define plugin-originated View/Case selection separately from editor-originated synchronization
      to avoid feedback loops.

### Search, filters, and merged items

- [ ] Store text query and Failed/Modified/New filter selection in controller state. (Currently owned
      by `SnapshotFilesPanel`; behavior is implemented but controller extraction remains.)
- [x] Apply the shared host-neutral projection rather than reimplementing filtering in Swing.
- [x] Load merged golden items for selected View or Case.
- [x] Keep each golden once and expose both source variants.
- [x] Do not clear selection merely because a user filter hides its Files row.

### Comparison and caches

- [ ] Key thumbnail/comparison caches by golden, source variant, and content identity/modification.
- [ ] Guard comparison decode/load results against stale selected-golden/source generations.
- [ ] Immediately clear or replace stale comparison content when a counterpart is unavailable.
- [ ] Represent identical, loading, changed, new/no HEAD, missing, ambiguous, stale-source, accepted,
      and error states.

### Controller tests

- [ ] Add pure tests for editor tab switching and same-file no-op behavior.
- [ ] Add tests for active file hidden by search/filter and Reveal.
- [ ] Add tests for View/Case/golden selection restoration and disappearance fallback.
- [ ] Add tests for automatic source selection and fallback across status transitions.
- [ ] Add tests for stale index and comparison results.
- [ ] Add tests proving Accept receives the displayed resolved source and ambiguity disables the
      action.
- [ ] Add tests for initial/background/empty/error states.

### Phase 2 verification

- [x] Run `./gradlew :core:test :public-plugin:test`.
- [x] Confirm the Gradle log reports `BUILD SUCCESSFUL`.
- [ ] Record which controller transitions are covered by unit tests versus platform tests.

Commands/checks run:

- 2026-07-30: `./gradlew :core:test :app:test :public-plugin:test :internal-plugin:test` —
  `BUILD SUCCESSFUL`.
- Pure controller tests cover stale generation, editor same-file no-op/tab switching, stable
  View/Case/golden restoration, and deterministic Case disappearance fallback.

Design deviations/blockers:

- _None recorded._

---

## Phase 3 — approved Swing UI

Implement only against the approved Phase 0 handoff. Map each completed artifact/frame to a concrete
component in the table below. If the handoff omits a consequential visual or interaction decision,
record a blocker and return to design review instead of inventing a new visual language.

### Shell and toolbar

- [x] Implement the approved Tool Window shell and hierarchy.
- [x] Remove the global Scope and Compare selectors; do not retain a hidden parallel workflow.
- [x] Implement approved Generate/Verify/Stop/settings/Figma command placement.
- [x] Preserve current settings and directory entry points.
- [ ] Implement command availability, running scope, and persistent result feedback.
- [x] Implement the approved Failed/Modified/New status legend with non-color cues.
- [x] Support approved wide, medium, and narrow toolbar behavior.

### Files

- [x] Implement Folder → View → Snapshot case rendering.
- [x] Implement expansion/collapse and selected/unselected states.
- [x] Implement failing-View folder counters.
- [x] Implement text search. (Match emphasis remains a fidelity refinement.)
- [x] Implement combinable Failed, Modified, and New filters.
- [ ] Implement initial indexing, background refresh, no snapshots, no query results, no filter
      results, and active-file-hidden states.
- [x] Implement Reveal and editor-follow feedback exactly as approved.
- [ ] Implement native open View/open generator/navigate-to-method actions. (File/generator open is
      implemented; method-offset navigation remains.)

### Goldens

- [x] Implement approved grid/list behavior and thumbnail sizing.
- [x] Render each merged golden once.
- [x] Render Failed, Modified, New, Unchanged, Ambiguous, and dual status chips.
- [ ] Preserve a single golden selection and approved scroll behavior.
- [x] Implement Working-only, Test-only, automatically resolved dual-source, missing-source, and
      ambiguous-source variants.
- [x] Place Accept and Accept all as approved.
- [x] Match the HTML handoff's compact Accept geometry: 22 px actions, text-sized width, pill
      Accept all, and rounded single Accept without native `JButton` chrome/insets.

### Compare

- [x] Adapt `CompareView` to the approved hierarchy and states.
- [ ] Preserve Side by side, Swipe, Onion skin, Pixel diff, and zoom.
- [x] Remove the local Test/Working selector and any remembered per-golden source state.
- [x] Automatically show changed/new Test output; otherwise show Working copy; fall back to the only
      available built-in variant.
- [x] Label the automatically selected source clearly in every comparison mode.
- [x] Automatically return to Working copy after Accept consumes the failing Test output.
- [ ] Implement identical, changed, new/no HEAD, loading, missing, ambiguous, stale-source, accepted,
      and error states.
- [x] Ensure changing mode fully replaces stale prior-mode content.
- [x] Add the reviewer-requested standalone-style opposite-corner action that opens a modeless,
      resizable Compare window. It follows live golden/source changes while keeping independent
      mode and zoom state.

### Responsive layout, splitters, and collapse

- [x] Implement approved wide layout.
- [x] Implement approved medium layout.
- [x] Implement approved narrow navigation/layout.
- [ ] Enforce practical pane minimums and approved collapse/restore behavior.
- [ ] Preserve/recover splitter proportions as approved.
- [x] Preserve View, Case, golden, scroll, zoom, and comparison mode through width-class changes;
      recompute the source from the same merged status rather than storing UI preference.

### Keyboard, focus, accessibility, and themes

- [ ] Implement approved focus order and keyboard navigation.
- [ ] Implement keyboard activation for View, Case, golden, commands, filters, and Compare controls.
- [ ] Restore focus predictably after modal confirmation, Accept, command completion, and pane
      transitions.
- [ ] Supply accessible names/descriptions and non-color status cues.
- [ ] Verify standard IDE scaling.
- [ ] Implement and compare light theme.
- [ ] Implement and compare dark theme.

### Design artifact → completed component

| Design artifact/frame | Component/file | Verification evidence | Deviation |
|---|---|---|---|
| _Pending Phase 0_ | _Pending_ | _Pending_ | _None_ |

### Phase 3 verification

- [x] Run `./gradlew :public-plugin:test`.
- [x] Confirm the Gradle log reports `BUILD SUCCESSFUL`.
- [ ] Compare every implemented surface with its mapped design artifact.
- [ ] Manually test wide, medium, and narrow widths.
- [ ] Manually test light and dark themes.
- [ ] Manually test keyboard/focus/accessibility paths.

Commands/checks run:

- 2026-07-30: `./gradlew :public-plugin:test :app:test` — `BUILD SUCCESSFUL`.
- 2026-07-30: `./gradlew :public-plugin:compileKotlin :public-plugin:compileTestKotlin` —
  `BUILD SUCCESSFUL`.
- 2026-07-30: `./gradlew :public-plugin:test` — `BUILD SUCCESSFUL`, including compact
  Accept sizing and bounded long-path tooltip coverage.
- 2026-07-30: `./gradlew :public-plugin:buildPlugin` — `BUILD SUCCESSFUL`; built
  `public-plugin/build/distributions/golden-diff-1.5.0-beta.13-dev.zip`.

Design deviations/blockers:

- Reviewer requested a detached Compare window after the approved HTML handoff. It deliberately
  reuses the standalone interaction/glyph and does not alter the responsive Tool Window hierarchy.

---

## Phase 4 — commands and Accept

### Configuration and placeholders

- [x] Add `verifyCaseCommand` to `ScreenshotSettings` state and persistence.
- [x] Add its public accessor and pass it through `ScreenshotSettings.toConfig()` so the plugin
      controller receives the value already supported by `GoldenDiffConfig`.
- [x] Add the Verify Case field to `ScreenshotConfigurable`.
- [x] Show command-specific placeholder help for whole-project, View, and Case fields.
- [x] Ensure existing placeholders describe the visible View.
- [x] Expand `{snapshot_file_name}`, `{snapshot_class_name}`, `{snapshot_file_path}`, and
      `{snapshot_method_name}` from the generator/Case.
- [x] Preserve existing settings migration/default behavior.
- [x] Add settings persistence and placeholder-expansion tests.

### Command execution

- [x] Keep Generate whole project and Verify whole project.
- [x] Implement Generate selected View.
- [x] Implement Verify selected View as a deduplicated generator/expanded-command batch.
- [x] Implement Verify selected Case.
- [x] Implement the approved fallback when Verify Case is blank.
- [x] Stream a batch through one IntelliJ Run output.
- [x] Execute batch entries sequentially and continue after ordinary test failures.
- [x] Make Stop terminate the active process and cancel the remaining queue.
- [x] Return typed success, failure, partial failure, and cancelled outcomes.
- [x] Present cancellation distinctly and emit no cancellation notification.
- [ ] Refresh statuses once after command/batch completion.
- [x] Add tests for queue progression, failure continuation, Stop/cancellation aggregation, and
      notification policy.

### Single Accept

- [x] Offer Accept only for actionable Test output.
- [x] Pass the exact resolved Test output shown in Compare to Accept; do not re-scan by basename when
      the action is invoked.
- [x] Implement accepting, success, failure, stale-source, ambiguity-blocked, and stale-action states.
- [x] Refresh the accepted golden and its generated counterpart once.
- [x] Preserve useful selection and navigation after Accept.

### Accept all

- [x] Derive Accept all candidates from the entire selected View, independent of selected Case,
      golden, search, and filters.
- [x] Freeze an explicit golden → generated-file candidate mapping for the confirmed batch and
      validate each source before writing.
- [x] Never expose project-wide Accept all.
- [x] Implement the approved confirmation with View name and candidate count.
- [x] Implement progress.
- [x] Continue after per-file failures and return a complete result.
- [x] Implement full success.
- [ ] Implement partial failure with unresolved-item detail. (Summary count is implemented; detailed
      retry list UI remains.)
- [ ] Implement retry of unresolved candidates only.
- [x] Implement `Nothing to accept` for stale/no-op actions.
- [ ] Suppress intermediate refreshes and perform one coherent refresh at completion.
- [x] Preserve View/Case/golden selection and successful accepts.
- [x] Keep failed items visible and actionable.
- [ ] Add controller/integration tests for every Accept all state.

### Phase 4 verification

- [x] Run `./gradlew :core:test :public-plugin:test`.
- [x] Confirm the Gradle log reports `BUILD SUCCESSFUL`.
- [ ] Manually run whole-project, View, Case, batch, fallback, Stop, partial-failure, and cancellation
      command flows in `runIde`.
- [ ] Confirm cancellation is not notified as failure.
- [ ] Manually verify single Accept and every Accept all state.

Commands/checks run:

- 2026-07-30: `./gradlew :core:test :app:test :public-plugin:test` — `BUILD SUCCESSFUL`.
- `BatchCommandExecutionTest`, settings persistence/help tests, and
  `GoldenAcceptResolvedTest` cover the implemented command/Accept contracts.

Design deviations/blockers:

- _None recorded._

---

## Phase 5 — VFS, editor, and Figma integration

### VFS refresh

- [ ] Handle in-place PNG content changes with targeted thumbnail/status/comparison invalidation.
- [ ] Handle PNG create/delete/move with an atomic structural soft rescan.
- [ ] Refresh the source-file index when relevant source files or matching configuration change.
- [ ] Debounce bursts of relevant VFS events.
- [ ] Ignore non-relevant paths and non-image changes unless they affect source indexing.
- [ ] Suppress intermediate refreshes during Generate/Verify/Accept batches and refresh once after.
- [ ] Preserve selection, expansion, scroll, and preview during soft rescans while recomputing the
      automatic source from refreshed merged status.
- [ ] Guard against stale async VFS refresh results.
- [ ] Verify generated roots are indexed once per refresh rather than once per golden.

### Editor-follow and navigation

- [ ] Opening tab `A` selects/reveals View `A` and shows its goldens.
- [ ] Switching tabs updates the selected View.
- [ ] Moving the caret within the same file does not reset View, Case, golden, or scroll.
- [ ] An active file with no snapshots shows the approved contextual empty state without clearing the
      Files tree.
- [ ] An active file hidden by search/filters shows the approved `Reveal A` behavior without silently
      clearing user constraints.
- [ ] Selecting a View or Case from Files works as a plugin-first workflow.
- [ ] Open View uses native editor navigation.
- [ ] Open generator/Case navigates to the generator and specific method when resolvable.
- [ ] Avoid editor/controller feedback loops.

### Figma extension

- [x] Preserve and extend the public `ExtraComparisonSource` contract with an optional filter icon.
- [x] Keep the internal plugin able to contribute `FigmaImageSource`.
- [x] Put Figma references in the shared Goldens grid with absent/loading/error/ready states.
- [x] Keep Figma separate from the merged Working copy/Test output source model.
- [x] Preserve the built-in golden selection independently when a Figma card is selected.
- [x] Add the data-dependent Figma-only filter chip and reset it when the selected scope has no Figma.
- [x] Ensure the public plugin works normally when the extension is absent or dynamically unloaded.

### Phase 5 verification

- [x] Run `./gradlew :core:test :public-plugin:test :internal-plugin:test`.
- [x] Confirm the Gradle log reports `BUILD SUCCESSFUL`.
- [ ] Add/verify tests for VFS refresh classification and debounce decisions.
- [ ] Manually test in-place and structural PNG changes in `runIde`.
- [ ] Manually test editor-follow, Reveal, plugin-first search, and View/Case navigation.
- [ ] Run `./gradlew :internal-plugin:runIde` and smoke-test Figma registration and mode states.

Commands/checks run:

- `./gradlew :core:test :public-plugin:test :internal-plugin:test` — `BUILD SUCCESSFUL`.
- `./gradlew :public-plugin:buildPlugin :internal-plugin:buildPlugin` — `BUILD SUCCESSFUL`.
- ZIP audit — `core.jar` only in the public plugin; `icons/figmaMark.svg` only in the internal
  plugin; no Compose, Jewel, or Skiko entries.

Design deviations/blockers:

- _None recorded._

---

## Phase 6 — documentation and full verification

### Documentation

- [ ] Update `docs/architecture.md` for the shared projection/model and plugin controller.
- [ ] Update `docs/features.md` for Files/View/Case, merged sources, editor-follow, commands, and
      Accept all.
- [ ] Update `docs/matching.md` for View/Case association and collision behavior.
- [ ] Update `docs/settings-reference.md` for Verify Case and placeholders.
- [ ] Update relevant framework guides with View/Case command examples.
- [ ] Update `docs/gotchas.md` with new refresh, threading, compatibility, or extension constraints.
- [ ] Record every approved design deviation in this plan and relevant permanent documentation.

### Automated verification

- [ ] Run `git diff --check`.
- [ ] Run `./gradlew :core:test :app:test :public-plugin:test :internal-plugin:test`.
- [ ] Confirm the Gradle log reports `BUILD SUCCESSFUL`.
- [ ] Run `./gradlew :public-plugin:buildPlugin`.
- [ ] Run `./gradlew :internal-plugin:buildPlugin`.
- [x] Do not run local Plugin Verifier; repository policy reserves it for CI.
- [x] Confirm every locally permitted test/build Gradle log reports `BUILD SUCCESSFUL`.

### Distribution and compatibility audit

- [ ] Record exact public and internal ZIP paths and versions.
- [ ] Confirm `core.jar` is present in the public ZIP.
- [ ] Confirm `core.jar` is absent from the internal ZIP.
- [ ] Confirm neither ZIP contains Compose, Jewel, or Skiko.
- [ ] Confirm plugin classes and packaged `core.jar` have class-file major version at most 61
      (Java 17).
- [ ] Confirm patched descriptors declare `since-build="241"` and no `until-build`.
- [ ] Confirm the internal descriptor still depends on the public plugin.

### Manual `runIde` verification

- [ ] Launch the public plugin in `runIde` and record IDE/build details.
- [ ] Test wide, medium, and narrow Tool Window behavior.
- [ ] Test light and dark themes.
- [ ] Test editor tab `A` → View `A` → goldens.
- [ ] Test same-file caret movement preserves golden selection and scroll.
- [ ] Test Files search by View name/path, generator path, and Case.
- [ ] Test combined Failed/Modified/New filters and folder counters.
- [ ] Test hidden-active-file Reveal and no-snapshot active file.
- [ ] Test View/Case selection and native editor navigation.
- [ ] Test Working-only, Test-only, dual-source automatic selection, unchanged-Test/changed-Working
      selection, and post-Accept fallback.
- [ ] Test all comparison modes, unchanged, missing, ambiguous, stale-source, loading, and error
      states.
- [ ] Test Verify View, Verify Case, fallback batch, Stop, partial failure, and cancellation.
- [ ] Test single Accept uses exactly the displayed Test output and blocks ambiguous/stale sources.
- [ ] Test Accept all success, partial retry, ambiguous/stale candidates, and nothing-to-accept.
- [ ] Launch through `:internal-plugin:runIde` and test Figma loading/error/ready and return flow.
- [ ] Compare every state with its mapped design artifact and record discrepancies.

### Standalone regression

- [ ] Run and record `./gradlew :app:test` after final `:core` changes.
- [ ] Confirm standalone Files/View/Case, merged-source, command-planning, and Accept contracts remain
      compatible.
- [ ] Perform a proportional standalone smoke test if shared behavior or public APIs changed.

Commands/checks run:

- _Not run yet._

Design deviations/blockers:

- _None recorded._

---

## Completion criteria

This plan is complete only when:

- [ ] Every checkbox matches the repository and verified behavior.
- [ ] Every approved design state maps to an implemented surface and evidence.
- [ ] Opening editor tab `A` automatically selects View `A` and shows its goldens.
- [ ] Search finds `A` by name or path and supports navigation to its snapshots.
- [ ] Same-file caret movement does not reset golden selection or scroll.
- [ ] The removed global Scope and Compare selectors have no remaining parallel state path.
- [ ] Working copy and Test output are merged without duplicate golden entries.
- [ ] Source selection is fully automatic: changed/new Test output wins, otherwise Working copy wins,
      with fallback to the only available variant and no manual selector or remembered preference.
- [ ] Accept promotes exactly the displayed/resolved Test output and never chooses an ambiguous
      basename match.
- [ ] Verify Case, Verify View batching, Stop, cancellation, Accept, and View-scoped Accept all work.
- [ ] Both plugins remain compatible with build 241+ and Java 17.
- [ ] The public ZIP alone packages `core.jar`; neither ZIP packages Compose/Jewel/Skiko.
- [ ] The Figma extension still loads through the public extension point.
- [ ] Standalone tests pass after shared logic moves to `:core`.
- [ ] All deviations, commands, manual checks, and remaining blockers are recorded honestly.

## Final handoff summary

Implementation status:

- _Not started._

Verification summary:

- _Not run yet._

Remaining blockers:

- _Design handoff pending._

Approved deviations:

- _None._
