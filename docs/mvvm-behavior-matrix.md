# MVVM behavior matrix

This is the regression inventory for the shared MVVM migration. Every migration slice must name the
IDs it changes, establish current behavior, and attach permanent test evidence before deleting the
legacy path.

## How to use the matrix

- **Existing evidence** identifies the current behavior source, not necessarily sufficient coverage.
- **Migration requirement** defines the permanent shared-contract or host-adapter proof.
- Add a row before implementing newly discovered observable behavior.
- Do not change expected behavior inside a behavior-preserving refactor. Handle product changes in a
  separate Red-Green-Refactor cycle and update the row explicitly.
- Replace legacy evidence with the final test class/method as slices complete.

## Selection and navigation

| ID | Required behavior | Existing evidence | Migration requirement |
|---|---|---|---|
| SEL-01 | A stale refresh cannot replace a newer result. | `PluginSnapshotControllerTest` | Shared contract test with manually completed requests. |
| SEL-02 | Equivalent refresh preserves View, Case, golden, and source-qualified grid selection by stable ID. | `PluginSnapshotControllerTest` | Shared contract plus both View adapters. |
| SEL-03 | Missing Case falls back deterministically to its View and first valid golden. | `PluginSnapshotControllerTest` | Shared contract test. |
| SEL-04 | Switching the active editor/file selects its View; repeating the same selection is a no-op. | `PluginSnapshotControllerTest`, `docs/features.md` | Shared intent test; editor/project mapping stays host-specific. |
| SEL-05 | Caret movement does not refresh or change selection. | `docs/gotchas.md` | Plugin adapter test proving no caret-driven intent. |
| SEL-06 | Selecting a Case limits goldens to that Case; Accept all remains View-scoped. | `docs/features.md` | Shared contract tests for selection and Accept scope. |
| SEL-07 | Search/filter constraints remain intact when hiding the active View; Reveal clears only required constraints. | `docs/features.md` | Shared state/intent contract test. |
| SEL-08 | Selecting a golden highlights and reveals its owning View without losing Case/golden identity. | `docs/features.md` | Shared contract plus View-adapter mapping tests. |

## Indexing, refresh, and files

| ID | Required behavior | Existing evidence | Migration requirement |
|---|---|---|---|
| REF-01 | Initial indexing, refreshing, ready, empty, unconfigured, and error are distinct states. | `PluginSnapshotController`, `AppState` | Shared state-transition contract. |
| REF-02 | A soft structural rescan keeps the previous usable tree/selection visible until replacement is ready. | `docs/features.md`, `docs/gotchas.md` | Shared contract with controlled completion. |
| REF-03 | Rewriting a PNG refreshes affected items and tree counters in place; create/delete/move performs structural rescan. | `PngDirectoryWatcherTest`, `PluginSnapshotControllerTest`, `AppState` | Shared refresh-decision contract plus watcher adapter tests. |
| REF-04 | Working-copy and Test-output changes refresh together regardless of displayed variant, and stale async completions cannot patch a newer refresh/project. | `PluginSnapshotControllerTest`, `docs/gotchas.md` | Shared refresh contract. |
| REF-05 | Generated roots are indexed once per refresh; ambiguity remains typed and blocks actions. | `GeneratedCounterpartIndexTest` | Core tests retained; shared presentation outcome test. |
| REF-06 | Untracked directories are expanded to individual files and non-ASCII paths remain intact. | `GitCliTest`, `docs/gotchas.md` | Core regression tests retained. |
| REF-07 | Source parsing/indexing and filesystem/git/image work never block the UI thread. | `docs/architecture.md`, `docs/gotchas.md` | Dispatcher/adapter contract tests with controlled executors. |
| REF-08 | Closing the workflow cancels owned work and suppresses every later state/effect publication. | No direct coverage | Shared lifecycle contract test before first cutover. |
| REF-09 | XCRESULT mode lists individual recent runs, persists one per project, falls back when missing, and rejects stale catalog results. | `XcodeResultGeneratedArtifactsTest`, `AppConfigTest`, app controller generations | Keep app-only workflow/adapter tests. |

## Comparison

| ID | Required behavior | Existing evidence | Migration requirement |
|---|---|---|---|
| CMP-01 | Actionable changed Test output wins; otherwise Working copy wins; the only available variant is the fallback. | `AutomaticBuiltInSourceTest`, `GoldenSourceSelectionTest` | Core tests retained plus shared presentation contract. |
| CMP-02 | Equal bytes show one preview and hide comparison modes; differing bytes expose all modes. | `docs/features.md` | Shared comparison-state contract. |
| CMP-03 | Missing HEAD, working copy, generated output, or configuration produces the correct typed single-view state. | `docs/features.md` | Shared contract covering every absence reason. |
| CMP-04 | Compare mode and zoom survive selection and layout changes. | `docs/features.md` | Shared mode state test; zoom rendering adaptation per View. |
| CMP-05 | A stale comparison/image completion cannot replace the current selection. | `AppState`, plugin generation logic | Shared request-identity contract. |
| CMP-06 | Extension comparison is review-only and never becomes Working/Test or Acceptable. | `PluginSnapshotControllerTest`, `docs/features.md` | Shared extension-card contract plus plugin adapter test. |

## Accept

| ID | Required behavior | Existing evidence | Migration requirement |
|---|---|---|---|
| ACC-01 | Accept is available only for an automatically selected differing Test output. | `GoldenAcceptTest`, `docs/features.md` | Shared eligibility/state contract. |
| ACC-02 | Accept validates frozen source identity and reports Missing, Ambiguous, Stale, I/O, Nothing-to-accept, or Success explicitly. | `GoldenAcceptResolvedTest` | Core tests retained; shared typed outcome transitions. |
| ACC-03 | Successful Accept returns presentation to Working copy and marks confirmation without disruptive rescan. | `AppState`, `docs/features.md` | Shared contract plus both host adapters. |
| ACC-04 | Accept all is View-scoped, confirms, reports progress, continues after failures, and retains only the retry subset. | `AppState`, plugin panel | Shared workflow contract with fake port. |
| ACC-05 | Accept/Accept all never acts on extension/Figma items. | `docs/features.md` | Shared invariant test. |

## Commands and notifications

| ID | Required behavior | Existing evidence | Migration requirement |
|---|---|---|---|
| CMD-01 | Empty command disables its action and preserves the configured scope/fallback label. | `docs/features.md` | Shared command-state contract plus View rendering tests. |
| CMD-02 | View verification expands unique generators once and runs them sequentially. | `SnapshotCommandPlanningTest`, `BatchCommandExecutionTest` | Shared planning contract and executor adapter contracts. |
| CMD-03 | Batch execution continues after test failure, supports cancellation, and exposes complete typed outcome/progress. | `BatchCommandExecutionTest`, `AppState` | Shared workflow contract; separate host executor tests. |
| CMD-04 | Watcher refreshes are suppressed during a command and exactly one refresh occurs after completion. | `docs/features.md`, host code | Shared orchestration contract. |
| CMD-05 | Completion notification is eligible only when backgrounded and command succeeded/failed; cancellation is silent. | `CompletionNotificationTest` | Core policy retained; shared effect test and host delivery tests. |
| CMD-06 | Plugin execution uses the IntelliJ Run tool window; desktop uses its process/log adapter. | `docs/gotchas.md` | Separate adapter contract/smoke tests; never share platform implementation. |
| CMD-07 | Selecting an iOS destination atomically rewrites every non-empty Xcode command; ambiguity or persistence failure changes none. | `XcodeDestinationsTest`, `AppConfigTest` | App-only parser/adapter and state-transition tests. |
| CMD-08 | Every Generate/Verify scope warns on selected-run device/platform/exact-OS mismatch; loading cannot bypass preflight; Cancel runs none and Run anyway bypasses once only in the originating project. | `XcodeRunWorkflowTest`, `AppState` common preflight | App-only workflow tests with a fake executor. |

## Settings, extensions, and shell

| ID | Required behavior | Existing evidence | Migration requirement |
|---|---|---|---|
| SET-01 | Command-only settings update actions without rescan; association changes invalidate relevant caches and rescan. | `AppConfigTest`, `AppState` | Shared change-impact policy and state contract. |
| SET-02 | Invalid regex/configuration is reported without corrupting the last usable workflow state. | `GoldenDiffConfigTest`, settings tests | Core validation retained; shared error-state contract. |
| SET-03 | IntelliJ and app persistence map to one host-neutral configuration model without format loss. | `ScreenshotConfigurableTest`, `AppConfigTest` | Host adapter round-trip tests. |
| SET-04 | Selected XCRESULT is separate per-project session state; selected device is derived only from persisted commands. | `AppConfigTest`, `XcodeDestinationsTest` | App-only persistence round-trip tests. |
| EXT-01 | Built-in and extension items merge with stable source-qualified IDs; filter resets outside extension scope. | `PluginSnapshotControllerTest` | Shared contract; plugin extension adapter test. |
| EXT-02 | A stale extension result cannot replace results for the current View. | `PluginSnapshotControllerTest` | Shared request-identity contract. |
| SHL-01 | App updater, onboarding, native windows, and plugin editor lifecycle remain outside shared workflow state. | Architecture contract | Architecture check and host-only tests. |

## Runtime invariants

| ID | Invariant | Required proof |
|---|---|---|
| INV-01 | Selected Case belongs to selected View. | State invariant test after generated intent sequences. |
| INV-02 | Selected golden belongs to the selected View/Case or is absent. | State invariant test after generated intent sequences. |
| INV-03 | A hidden item cannot remain the active visible grid item; durable selection is not silently destroyed. | Filter/selection invariant tests. |
| INV-04 | Lower request generation never mutates state or emits an effect. | Controlled async contract tests. |
| INV-05 | Ambiguous or extension sources never expose Accept. | Eligibility invariant tests. |
| INV-06 | State caused by an intent is published before its effect. | Subscriber/effect trace test. |
| INV-07 | Reentrant dispatch is queued and processed deterministically, never recursively. | Trace test with dispatch from subscriber. |
| INV-08 | Effects carry unique increasing IDs and are not replayed to a replacement subscriber. | Effect delivery contract test. |
| INV-09 | `close()` is idempotent and suppresses all later state/effect publication. | Lifecycle contract test. |
| INV-10 | One refresh does not regress to per-golden filesystem walks. | Core/index instrumentation test or explicit scan-count assertion. |

Start with table-driven sequences. Add deterministic generated intent sequences for snapshot
selection once the shared reducer exists; no production property-testing dependency is required.
