# Snapshot workflow improvements — implementation plan

Status: **ready for implementation — Design Agent handoff reviewed**

This is the working plan for the agent that will implement the snapshot-workflow UI changes. The
Design Agent does not maintain this document. The implementation agent owns it and updates the
checkboxes as work progresses.

Phase 0 is complete. The implementation agent must use the reviewed handoff references below and
keep this document current while implementing.

## Progress rules

- Every implementation task starts as `[ ]`.
- Change a task to `[x]` only after the code is implemented and proportionately verified.
- A partially completed task remains `[ ]` and gets a short progress note.
- After each phase, record the exact tests or manual checks run and their results.
- Record blockers and deliberate deviations from the design under the affected phase.
- Do not mark a phase complete only because code was written when its tests or required manual checks
  are still outstanding.
- Before handoff, reconcile every checkbox with the actual repository state.

## Settled product decisions

- File-list, filtering, merged-source, comparison, and Accept all changes apply to the standalone
  Compose Desktop app only.
- Completion notifications apply to the standalone app and the public IDE plugin.
- A snapshot source file is shown only when current matching rules associate it with at least one
  existing golden.
- A source file is failed when at least one associated Test output is new or differs from Git HEAD.
  Command exit status is not used as the per-file failure status.
- Working copy and Test output are detected together. One golden appears once and carries independent
  source variants.
- Test output is the default comparison variant when both variants are available.
- `Accept all` applies to all acceptable Test outputs associated with the current source file, not to
  the whole project or only the currently filtered cards.
- Notifications are enabled by default, emitted for success/failure only when the host is in the
  background, and open the relevant command log when clicked.
- The Design Agent handoff is a high-fidelity redesign reference for the complete standalone window,
  not only the new controls. Standalone implementation should reproduce its shell, panes, states,
  hierarchy, spacing, status semantics, and microcopy using the existing Compose design system.
- The standalone Notifications setting is a separate Settings navigation section, not part of
  Commands.
- The comparison has one active golden and no tab strip. Source selection remains in the Files pane.
- The selected Test/Working source and onion-skin opacity are remembered per golden for the current
  project session.
- A dedicated Xcode `test-without-building` workflow is out of scope.

---

## Phase 0 — receive and map the design handoff

This phase was completed during handoff review on 2026-07-29.

- [x] Inventory every file delivered by the Design Agent and list its repository path below.
- [x] Identify the canonical design state for grouped files, flat files, Failed only, combined
      Working copy/Test output, local source switching, Accept all, Settings, and notifications.
- [x] Map each design state to the current standalone component:
      `ProjectPane`, `GoldensPane`, `ComparePane`, `GoldenDiffWindow`, or `SettingsWindow`.
- [x] Map plugin notification settings to `ScreenshotConfigurable` and completion behavior to
      `ScreenshotPanel`.
- [x] Record interaction details that are not explicit in static designs.
- [x] Resolve discrepancies between the settled product decisions above and the delivered design
      before implementation.
- [x] Replace the placeholder reference table below with exact artifact paths and state names.

### Design reference table

| Behavior | Design artifact/state | Implementation surface |
|---|---|---|
| Main shell, panes, toolbar and legend | `Golden Diff.dc.html`: `Dark` / `Light`; README §§1–2 | `GoldenDiffWindow`, pane composables, theme/widgets |
| Grouped snapshot files | `Golden Diff.dc.html`: `Dark`, `groupByFolders=true`; README §1 “Left pane — Files” | `ProjectPane` |
| Flat snapshot files | `Golden Diff.dc.html`: `Dark`, `groupByFolders=false`; README §1 | `ProjectPane` |
| Failed-only/search states | Main Files controls plus `States`: Files no failures / filtered empty; README §§1, 6 | `ProjectPane` |
| File loading/indexing/empty states | `States`: Files loading / empty / indexing; README §6 | `ProjectPane` |
| Combined project changes | Main window with Scope=`Project changes`; README §§1, “Interactions & Behavior” | `GoldensPane`, scope toolbar |
| Golden tabs and status dots | Main `Dark` / `Light`; README §1 and “Interactions & Behavior” | `GoldenDiffWindow`, `AppState` |
| Local source selection | Dual-source selected golden in Main; README §1 “Right pane — Compare” | `ComparePane` |
| Compare modes and expanded view | Main Side by side / Swipe / Onion skin / Pixel diff and expand action; README §1 | `ComparePane`, `ComparisonWindow` |
| Compare empty/error states | Main unchanged banner and `States`: No counterpart; README §§1, 6 | `ComparePane` |
| Accept all normal flow | Main Goldens header plus confirm/progress overlays; README §1 | `GoldensPane`, new confirmation/progress UI |
| Accept all result states | `States`: confirm / progress / success / partial failure / nothing to accept; README §6 | `AppState`, Goldens feedback UI |
| Standalone notification setting | `Settings` → `Notifications`, including blocked variant; README §4 | `SettingsWindow`, `SettingsSections` |
| Notification examples | `Settings` previews and `States`: Notifications blocked; README §§4, 6 | standalone notification adapter/settings |
| Native Settings entry | `macOS Menu`: project open / no project open; README §5 | application menu, `SettingsWindow` |
| Quick Open | `Quick Open`; README §3 | existing `QuickOpenDialog` |
| Settings shell and all sections | `Settings`; README §4 | `SettingsWindow`, `SettingsSections` |
| Plugin notification setting | No plugin visual in this standalone handoff; use its notification behavior/microcopy and existing Swing settings conventions | `ScreenshotConfigurable` |
| Plugin completion notification | No plugin visual in this standalone handoff; use existing IntelliJ notification conventions | `ScreenshotPanel` |

### Handoff files and review notes

- Source directory:
  `/Users/dkwasniak/Downloads/design_handoff_golden_diff_redesign 4`
- Canonical interactive reference:
  `/Users/dkwasniak/Downloads/design_handoff_golden_diff_redesign 4/Golden Diff.dc.html`
- Canonical written specification:
  `/Users/dkwasniak/Downloads/design_handoff_golden_diff_redesign 4/README.md`
- The prototype-only top screen switcher and tweaks/state controls must not ship.
- `Golden Diff.dc.html` references `./support.js`, but that runtime was omitted from this handoff.
  Review used the compatible runtime at
  `/Users/dkwasniak/Downloads/design_handoff_command_controls/support.js`
  (SHA-1 `fc0c0276854c97c415b1b38c2961f6cafe79780c`). Product implementation does not depend on it.
- The HTML is a reference, not production code and not a WebView payload.

### Resolved design/product discrepancies

- The handoff renders `Group` as a compact labeled switch rather than the earlier checkbox request.
  Follow the delivered switch.
- Keep the existing project picker/recent-project control and fit it into the delivered toolbar.
  The prototype omits it, but removing project switching is a functional regression.
- Keep single-item `Accept` restricted to a differing Test output. Do not show it for Working copy,
  even though prototype logic makes it visible for any differing source; Working copy already is the
  golden on disk.
- `Accept all` uses the delivered destructive confirmation and result states and remains Test-output
  only.
- The standalone app gets a separate Notifications settings section. The plugin gets the same setting
  using its existing settings structure; no plugin-list redesign is implied.
- The macOS menu mockup documents the Settings entry and no-project behavior. Preserve standard
  system-owned items such as Services/Hide rather than replacing the whole native menu literally.
- Preserve existing Generate/Verify whole-project/current-file command choices. The prototype's
  carets are not permission to remove current command variants.

### Phase 0 verification

- [x] Review the completed mapping against every delivered artifact.
- [x] Confirm that no implementation decision still depends on an unspecified design state.

Tests/checks run:

- Read the complete README and inspected the interactive state definitions in `Golden Diff.dc.html`.
- Rendered and visually reviewed Main Dark, Project changes + flat Files, Notifications Settings,
  macOS Menu, and the full States gallery with the compatible `support.js` runtime.

---

## Phase 1 — shared merged-change model

Build the tool-agnostic data model and scanning behavior in `:core` before changing the UI.

- [x] Introduce a merged golden item that identifies one golden file and stores independent
      Working copy and Test output statuses.
- [x] Preserve the existing `MODIFIED`, `NEW`, and `UNCHANGED` semantics for each individual source.
- [x] Define merged display precedence as Failed/Test output change, then Working copy modified, then
      Working copy new, then unchanged.
- [x] Extend `ChangeScanner` with a scan that obtains Working copy and generated changes, merges them
      by normalized golden path, and returns one item per golden.
- [x] Keep Working copy classification based on `git status --porcelain=v1 -z
      --untracked-files=all`.
- [x] Keep generated classification based on generated-file matching and byte comparison with HEAD.
- [x] Ensure a missing generated counterpart is absence of the Test output variant, not an unchanged
      Test output.
- [x] Sort merged Project changes by the settled precedence and invariant project-relative path.
- [x] Keep all file/git/image I/O off UI threads.
- [x] Add `:core` tests for Working-copy-only, Test-output-only, dual-source, new-file,
      deduplication, missing counterpart, and stable ordering scenarios.

Do not add IntelliJ, Swing, or Compose dependencies to `:core`.

### Phase 1 verification

- [x] Run `./gradlew :core:test`.
- [x] Read the Gradle log and confirm `BUILD SUCCESSFUL`.

Tests/checks run:

- `./gradlew :core:test` — `BUILD SUCCESSFUL` (2026-07-29).

---

## Phase 2 — snapshot-related source-file index

Replace the standalone all-files navigation data with files that match existing goldens.

- [x] Build a background index from the existing `ProjectFileIndex` candidates.
- [x] For each viable source file, reuse `GenericScreenExtractor`, the configured match mode, and
      `GoldenFinder`; include the file only when at least one golden matches.
- [x] Store the matched golden paths with the source-file entry so later status aggregation does not
      repeat the complete match scan.
- [x] Compute each source file's failed count from associated merged items whose Test output is new or
      modified.
- [x] Compute each source file's displayed status with precedence Failed, Modified, New; use the
      associated Test output status only for Failed and Working copy status for Modified/New.
- [x] Aggregate failed-file counts into ancestor directories.
- [x] Invalidate/rebuild the snapshot-file index when project, golden directories, matching mode,
      matching regexes, patterns, or excluded suffixes change.
- [x] Refresh affected failure counts after generated-output watcher events and Verify completion.
- [x] Do not attempt to discover a brand-new snapshot test without an existing golden.
- [x] Add pure tests for source-file inclusion, duplicate golden associations, directory aggregation,
      and configuration invalidation inputs.

### Phase 2 verification

- [x] Run `./gradlew :core:test :app:test`.
- [x] Read the Gradle log and confirm `BUILD SUCCESSFUL`.

Tests/checks run:

- `./gradlew :core:test :app:test :public-plugin:test` — `BUILD SUCCESSFUL` (2026-07-29).

---

## Phase 3 — standalone window shell and file navigation UI

Implement the delivered designs on top of the Phase 2 index.

- [x] Reproduce the delivered standalone window shell: toolbar, status legend/command row, golden-tab
      strip, padded three-pane body, floating rounded panes, splitters, and status bar.
- [x] Extend the existing theme tokens with a distinct amber Modified status and the delivered panel,
      border, hover, selection, and Accept colors for both appearances.
- [x] Keep current update/build badges, status chip, project picker, and recent-project behavior while
      adapting them to the delivered hierarchy.
- [x] Preserve automatic refresh; do not add a Refresh button.
- [x] Reproduce the delivered Quick Open overlay and keyboard hints while preserving `⇧⌘O`, arrow
      navigation, Enter, Escape, and click-outside dismissal.
- [x] Keep the Files pane collapsible and resizable, including the narrow restore affordance when
      collapsed.
- [x] Replace the all-project-files input to `ProjectPane` with the snapshot-related source-file
      index.
- [x] Add the compact `Group` switch from the Files header, defaulting to enabled.
- [x] In grouped mode, render a source-root-aware directory hierarchy: keep module paths and actual
      package branches, collapse conventional `src/<source-set>/<language>` plus the shared package
      prefix, show shared ancestors once, and keep the complete original path in the folder tooltip.
- [x] Allow grouped folders to be expanded/collapsed; searching temporarily expands matching paths.
- [x] Sort folder headers by project-relative path and files within a folder alphabetically by
      filename.
- [x] When grouping is disabled, render one flat row per source file and show enough path context to
      disambiguate duplicate filenames.
- [x] Sort the flat view by failed status first and then `ProjectFileIndex.PATH_ORDER`.
- [x] Add `Failed only`.
- [x] Compose text search with `Failed only` rather than letting either filter replace the other.
- [x] Search against the complete project-relative source path, not only the filename.
- [x] In grouped mode, hide a folder header only when none of its files match the active filters.
- [x] Keep a folder's failure count based on all files admitted by `Failed only`, not only those that
      happen to match the current search string.
- [x] Display the delivered Failed/Modified/New chips on file rows and red failed-file count badges on
      folder headers.
- [x] Make a single click on a file select it and load its goldens, as shown in the handoff; preserve
      quick-open and missing-source handling while keeping one active comparison.
- [x] Implement the mapped loading, indexing, no-files, no-failures, and filtered-empty states.
- [x] During background re-indexing, keep the previous list visible and show the inline
      `Indexing snapshots…` banner; replace the list only when the new index is ready.
- [x] `Show all files` in the no-failures state disables `Failed only`; `Clear search` clears only the
      search query.
- [x] Add tests for grouped/flat projection, combined filters, folder-header retention, counts, and
      ordering.

### Phase 3 verification

- [x] Run `./gradlew :app:test`.
- [ ] Manually compare the window shell and each navigation state with the Main and States references.
- [ ] Confirm keyboard navigation, single-click file selection, and Quick Open still work.
- [ ] Confirm the existing project menu and recent-project switching remain available.
- [x] Read the Gradle log and confirm `BUILD SUCCESSFUL`.

Tests/checks run:

- Automated projection/filter/order tests pass. Arrow navigation is covered for initial selection,
  next/previous movement, and list boundaries. Grouped projection tests cover source-root/package
  compaction, preserved module branches, shared ancestors, nested files, collapsed descendants, and
  aggregated failure counts. The source-root-aware tree intentionally supersedes the handoff's flat
  full-path folder headers per product direction on 2026-07-29. Manual visual and remaining
  interaction checks remain pending.
- `./gradlew :app:test` — `BUILD SUCCESSFUL` (2026-07-29).

---

## Phase 4 — merged sources in standalone lists and comparison

- [x] Remove the standalone global `Compare: Working copy / Test output` selector.
- [x] In Current file, scan status for both source variants for every matched golden.
- [x] In Project changes, use the merged scan from Phase 1 and show one card per golden.
- [x] Title the middle pane `Goldens` in Current file and `Project changes` in project scope, with the
      delivered screenshot count.
- [x] Render the adaptive thumbnail grid and status bars from the handoff; a card uses the highest
      precedence Failed/Modified/New status while its model retains both variants.
- [x] Preserve the existing 10–200% thumbnail stepper behavior while matching the delivered header
      placement and default 100% presentation.
- [x] Remove the source-file and golden tab strips. Selecting a card replaces the single active
      comparison without changing the selected source file.
- [x] Scope per-file Generate/Verify and `Accept all` to the source file that owns the active golden
      tab/current Files selection, never to a stale previously selected source.
- [x] Add local source selection to `ComparePane` only when both variants exist.
- [x] Default local selection to Test output when both variants exist.
- [x] Remember Test/Working selection and onion-skin opacity per golden for the current project
      session.
- [x] When one variant disappears after a refresh or Accept, fall back to the remaining variant
      without clearing the golden selection.
- [x] Update comparison cache keys so Working copy and Test output results cannot collide.
- [x] Preserve thumbnail cache behavior, zoom, comparison mode, and detached comparison behavior.
- [x] Match the delivered compare header actions and keep single `Accept` visible only for a differing
      Test output.
- [x] For unchanged content, hide the mode row and show the delivered unchanged banner plus one
      centered image.
- [x] For a missing counterpart, clear any previous comparison immediately and show `No working copy
      match`; never leave stale comparison content visible.
- [x] Ensure switching Side by side, Swipe, Onion skin, and Pixel diff fully replaces the prior mode
      content.
- [x] Place the onion opacity row above the image body and bind it to the selected golden's remembered
      opacity.
- [x] Adapt `ComparisonWindow` to the delivered expanded modal with its own zoom while reusing the
      selected golden, source, compare mode, and onion opacity.
- [x] Update PNG watcher refresh handling so changes in either source refresh the merged item without
      blanking the list.
- [x] After successful Verify, refresh both sources; do not perform a global-source switch.
- [x] Implement missing-counterpart and source-unavailable states from the design handoff.
- [x] Add tests for local-source defaulting, fallback, merged ordering, and watcher-driven state
      transitions where testable.

### Phase 4 verification

- [x] Run `./gradlew :core:test :app:test`.
- [ ] Manually verify Working-copy-only, Test-output-only, and dual-source goldens.
- [ ] Verify Current file and Project changes against their Phase 0 design references.
- [ ] Verify single-comparison selection and transient per-golden state.
- [ ] Verify switching modes and missing counterparts never shows a stale image.
- [ ] Confirm list selection and comparison do not reset during soft refresh.
- [x] Read the Gradle log and confirm `BUILD SUCCESSFUL`.

Tests/checks run:

- Automated merged-model/cache/projection coverage passes. Manual comparison-mode checks remain pending.
- Source-selection tests cover Test-output defaulting, remembered Working copy, disappearance
  fallback, and Test-output-only items. Watcher refresh-decision tests cover in-place modification,
  structural rescan, Project changes rescan, overflow, and empty batches.
- `./gradlew :core:test :app:test` — `BUILD SUCCESSFUL` (2026-07-29).

---

## Phase 5 — Accept all for the current file

- [x] Add a core bulk-accept operation that accepts an explicit set of golden files and returns
      per-file success/failure results.
- [x] Resolve every generated counterpart with the same rules used by the comparison view and
      single-file `GoldenAccept`.
- [x] Do not stop after the first failure; return a complete partial-failure result.
- [x] Never delete a generated file unless its bytes were successfully written to the corresponding
      golden.
- [x] In the standalone state, derive candidates from all acceptable Test output variants associated
      with the current source file, independent of selected card and UI filters.
- [x] Add `Accept all (N)` in the location defined by the design handoff.
- [x] Show it only in Current file scope when `N > 0`; hide it when there is nothing to accept and
      never expose it in Project changes.
- [x] Implement the destructive confirmation `Accept all Test outputs?`, naming the current source
      file and candidate count, with Cancel and `Accept all (N)`.
- [x] Implement modal progress with `Accepting X of N…`.
- [x] On full success, close progress and show the transient `All N snapshots accepted` toast.
- [x] On partial failure, show accepted/total counts, the failed golden and reason, plus
      `Retry remaining`; retry only the unresolved subset.
- [x] Implement the no-op `Nothing to accept` state for a stale action/candidate race.
- [x] Suppress intermediate watcher refreshes and perform one targeted update when the batch ends.
- [x] Preserve the selected source file, selected golden, grid scroll, and successfully accepted
      cards.
- [x] Leave failed items actionable and visibly unresolved.
- [x] Add core tests for all-success, no counterpart, read/write failure, mixed success, and generated
      cleanup.

### Phase 5 verification

- [x] Run `./gradlew :core:test :app:test`.
- [ ] Manually compare every Accept all state with its Phase 0 design reference.
- [ ] Verify a partial failure does not lose successful accepts or current UI context.
- [x] Verify `Accept all` is hidden at zero in Current file and absent in Project changes.
- [x] Read the Gradle log and confirm `BUILD SUCCESSFUL`.

Tests/checks run:

- Bulk success, missing-counterpart, mixed-result and generated-cleanup tests pass. Manual modal-state
  review remains pending.
- Explicit unreadable-generated and unwritable-golden tests verify typed I/O failures and preserve
  generated files for retry; all seven `GoldenAcceptTest` cases executed with zero skips.
- Visibility policy test verifies `Accept all` appears only in Current file with at least one
  candidate. This intentionally supersedes the handoff's disabled-at-zero state per product
  direction on 2026-07-29.
- `./gradlew :core:test :app:test` — `BUILD SUCCESSFUL` (2026-07-29).

---

## Phase 6 — Settings shell and native macOS Settings entry

- [x] Align the standalone Settings window with the delivered shell: separate window, left navigation,
      content area, optional matching preview area, and Cancel/OK footer.
- [x] Preserve the existing settings behavior and values across Directories, Test output matching,
      Golden matching, Filtering, Display, Commands, and Privacy.
- [x] Keep the live matching preview restricted to Test output matching, Golden matching, and
      Filtering as shown in the handoff.
- [x] Reserve the new Notifications destination between Commands and Privacy for Phase 7.
- [x] Add a native `Settings…` item with `⌘,` to the Golden Diff application menu.
- [x] Route it to the existing `settingsVisible`/`SettingsWindow` flow.
- [x] Keep the item discoverable without an open project.
- [x] With no project open, allow Settings to open but present project-dependent controls as requiring
      an open project; never save their values to an unintended project.
- [x] Preserve standard macOS-owned application menu items and shortcuts; add Settings rather than
      replacing the native menu with a custom imitation of the design reference.
- [x] Keep the existing in-window Settings entry working.

### Phase 6 verification

- [ ] Verify `⌘,` and the menu item in `:app:run`.
- [ ] Verify the same behavior in a packaged macOS application.
- [ ] Compare the Settings shell and all existing sections with the Phase 0 reference.
- [ ] Compare the no-project state with its Phase 0 design reference.

Tests/checks run:

- Implementation compiles; native menu and packaged-app manual verification remain pending.

---

## Phase 7 — task-completion notifications

### Shared policy

- [x] Add a tool-agnostic, pure notification decision model covering host foreground state, setting
      enabled/disabled, success, failure, cancellation, command kind, and command scope.
- [x] Notify only when enabled, the host is in the background, and the command ended in success or
      failure.
- [x] Define message content containing Generate/Verify, whole-project/current-file scope, project or
      filename, and result.
- [x] Add tests for every eligibility branch and message variant.

### Standalone app

- [x] Persist `Notify when tasks finish`, defaulting to enabled.
- [x] Add a separate `Notifications` destination between Commands and Privacy in Settings.
- [x] Add the delivered toggle, final explanatory microcopy, and success/failure preview cards; show
      previews only while the toggle is enabled.
- [ ] When macOS notifications are blocked, show the inline warning directly below the toggle with
      `Open System Settings ▸ Notifications`.
- [x] Deliver a macOS system notification after eligible command completion.
- [x] Detect whether the app is foregrounded.
- [x] Add DEV BUILD actions in Diagnostics for sending representative success and failure
      notifications through the production notification adapter.
- [ ] On notification click, activate Golden Diff and open the latest command log window.
- [ ] Route the blocked-state action to the Golden Diff notification page in macOS System Settings.
- [x] Keep the existing in-app command status chip behavior.

### Public plugin

- [x] Persist the same default-on setting in `ScreenshotSettings`.
- [x] Add the setting using the existing Swing Settings conventions. The standalone Notifications
      page is a behavioral/microcopy reference, not a mandate to redesign plugin navigation.
- [x] Emit eligible notifications through the IntelliJ Platform notification API.
- [x] Detect whether the IDE is active before notifying.
- [x] On notification action, activate the relevant Run content/tool window for the completed
      command.
- [x] Do not add notification APIs to `:core`.

### Phase 7 verification

- [x] Run `./gradlew :core:test :app:test :public-plugin:test`.
- [ ] Manually verify standalone success, failure, foreground suppression, disabled setting, click
      routing, and blocked permission.
- [ ] Compare standalone Notifications Settings and previews with the Phase 0 reference.
- [ ] Manually verify plugin success, failure, foreground suppression, disabled setting, and Run
      window routing.
- [x] Read the Gradle log and confirm `BUILD SUCCESSFUL`.

Tests/checks run:

- Pure policy, app settings/delivery, and IntelliJ notification integration pass automated tests.
- DEV BUILD Diagnostics includes success/failure notification actions using the shared policy and
  production macOS adapter.
- `./gradlew :app:test` — `BUILD SUCCESSFUL` (2026-07-29).
  macOS blocked-permission detection, Settings deep-link targeting, and notification-click routing
  remain pending because the current `osascript` delivery adapter exposes no click callback.

---

## Phase 8 — regression, packaging, and documentation

- [x] Update `docs/features.md` for snapshot-only navigation, merged sources, Accept all, native
      Settings, and notifications.
- [x] Update `docs/architecture.md` for new core models/services and host notification adapters.
- [x] Update `docs/gotchas.md` for any new watcher, menu, or packaged-notification constraints found
      during implementation.
- [x] Run `./gradlew :core:test :app:test :public-plugin:test :internal-plugin:test`.
- [x] Run `./gradlew :public-plugin:buildPlugin`.
- [x] Build/package the standalone app with a full JDK containing `jpackage`.
- [x] Confirm `core.jar` remains packaged only in the public plugin ZIP.
- [x] Confirm neither plugin ZIP contains Compose, Jewel, or Skiko.
- [ ] Manually run the end-to-end Current file, Project changes, Generate, Verify, single Accept,
      Accept all, Settings, and notification flows.
- [ ] Compare the completed Dark, Light, Quick Open, Settings, macOS Menu, and States UI against every
      Phase 0 reference and the handoff's Design system page.
- [x] Verify the prototype-only top switcher and tweaks controls were not implemented.
- [x] Record every approved visual or behavioral deviation from the handoff.
- [x] Reconcile all checkboxes with the actual repository state.

Tests/checks run:

- `./gradlew :core:test :app:test :public-plugin:test :internal-plugin:test :public-plugin:buildPlugin`
  — `BUILD SUCCESSFUL`.
- `./gradlew :app:packageDmg -PappJavaHome=/Users/dkwasniak/Library/Java/JavaVirtualMachines/openjdk-26.0.1/Contents/Home`
  — `BUILD SUCCESSFUL`; produced `Golden Diff-1.5.0.dmg`.
- Runtime image contains `java.net.http`.
- Plugin distributions inspected once after the final build: `core.jar` is present only in the public
  plugin; neither distribution contains Compose, Jewel, or Skiko.
- `git diff --check` passes.

Deliberate deviations / remaining platform work:

- The standalone notification adapter uses macOS `osascript` delivery. It cannot observe the system
  permission state or notification clicks, so blocked-permission UI, the System Settings deep link
  from that state, and click-to-open-log remain unchecked.
- Manual end-to-end and pixel-level design comparisons remain unchecked; no claim is made for those
  states based only on compilation or unit tests.
