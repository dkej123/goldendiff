# Shared MVVM refactor plan

This plan migrates the plugin and standalone application toward one shared presentation workflow.
Swing and Compose remain separate Views. Platform integrations remain separate adapters. State,
intents, effects, presentation rules, and host-neutral use cases become shared and are developed
through incremental TDD.

## Contents

1. Target architecture
2. Shared and host-specific responsibilities
3. Presentation contracts
4. Runtime state and effect contract
5. TDD migration harness
6. Migration slices
7. Validation gates
8. Completion criteria

## 1. Target architecture

Add a plain JVM `:presentation` module:

```text
Swing Views ------------------\
                               >-- :presentation --> :core
Compose Views ----------------/
       |                              ^
       v                              |
host adapters implement presentation ports
```

`:presentation` must:

- use `compileOnly(project(":core"))` and `compileOnly(kotlin("stdlib"))`; both hosts already provide
  `:core`;
- emit Java 17 bytecode so the public plugin can package it for build 241+;
- contain no Compose, Swing, IntelliJ, Skiko, Jewel, coroutine-runtime, vendor-SDK, filesystem-watcher,
  or operating-system types;
- expose deterministic state transitions that can be tested with JUnit and fakes;
- be `implementation` in `:public-plugin` and `:app`;
- remain absent from `:internal-plugin` unless a host-neutral public contract genuinely requires it.

Do not move presentation state into `:core`. Domain policy and presentation orchestration have
different reasons to change.

## 2. Shared and host-specific responsibilities

Share:

- Files → Goldens → Compare workflow state;
- View/Case/golden selection and stable identities;
- load/refresh/error states and stale-generation rejection;
- search/filter state and Reveal behavior;
- merged Working-copy/Test-output presentation;
- comparison source/mode selection policy;
- Accept and Accept-all workflow state;
- command planning, progress, cancellation result, and completion state;
- notification eligibility and host-neutral effect data;
- settings editing/validation state that is common to both hosts.

Keep host-specific:

- Swing and Compose rendering;
- IntelliJ PSI/editor/VFS/Run tool window adapters;
- desktop project selection, WatchService, process log, CLI, and macOS adapters;
- EDT and Compose-scope dispatch;
- settings persistence implementation;
- notifications delivery;
- app updater, onboarding, native window visibility, and plugin-only editor synchronization;
- Figma extension registration and IntelliJ classloader integration.

The goal is shared behavior under two Views, not removal of necessary host adapters.

## 3. Presentation contracts

Split by cohesive feature rather than creating one god ViewModel. Candidate contracts:

```text
SnapshotViewModel
ComparisonViewModel
CommandViewModel
SettingsViewModel
WorkspaceCoordinator
```

Each feature should define:

- an immutable `State`;
- a sealed `Intent` for user and host inputs;
- a sealed `Effect` for one-shot host actions;
- narrow ports for real platform differences;
- explicit async result intents carrying request identity/generation.

Views may hold only ephemeral rendering state. They render `State`, dispatch `Intent`, and execute or
translate `Effect` through host adapters.

Avoid a mandatory reactive-framework dependency. A small subscription/state-publication contract is
safer across IntelliJ platform versions and the packaged Compose app. Let each View adapt publication
to Swing rendering or Compose snapshot state.

## 4. Runtime state and effect contract

### Intent processing

- Process all intents through one serialized queue owned by the ViewModel.
- Queue an intent dispatched from a subscriber/effect handler and process it after the current
  transition; do not recurse into the reducer.
- Let host adapters marshal external callbacks onto that queue. Never mutate shared state directly
  from a worker, EDT callback, Compose coroutine, watcher, or vendor callback.
- Represent asynchronous completion as a typed result intent carrying request identity/generation.
- Ignore stale, cancelled, and post-close result intents.

### State publication

- Keep state immutable from the consumer's perspective.
- Give a new subscriber the current state immediately.
- Publish only complete states whose invariants hold.
- Publish the new state before any effect caused by the same intent.
- Treat a semantic no-op as no publication unless repeated publication is explicitly part of the
  contract.

### Effect delivery

- Keep confirmation, progress, retry data, selected items, and recoverable errors in durable state.
- Execute domain/infrastructure work through injected ports; feed typed outcomes back as intents.
- Reserve effects for transient host UI actions such as focus, reveal, open URL, copy, or host
  notification delivery.
- Give every effect a monotonically increasing `EffectId`.
- Deliver an effect at most once to the currently attached host; do not replay it to a later
  subscriber.
- Require the host subscription to be installed before it dispatches intents capable of producing
  effects.
- Let host adapters deduplicate by `EffectId` across framework redraw/rebinding.

Exactly-once delivery across a process crash is not promised. Anything that must survive detachment
or restart belongs in state or persistence, not an effect.

### Lifecycle

- Make `close()` idempotent.
- Cancel owned requests/subscriptions on close.
- Publish no state or effect after close.
- Do not let a ViewModel own host windows, Swing/Compose components, project lifecycle objects, or
  framework scopes. The composition root owns the ViewModel lifetime.

Cover all rules above with deterministic contract tests before sharing the first ViewModel.

## 5. TDD migration harness

### Characterize current behavior

Before moving a workflow:

1. List its observable states, intents, effects, ordering, failures, cancellation, and stale-result
   behavior.
2. Reuse existing tests where they express public behavior.
3. Add missing tests against the current seam.
4. Run them and record a green baseline.

Use `PluginSnapshotControllerTest` as the initial behavioral source for snapshot selection and stale
generation handling. Add characterization around `AppState` behavior through a narrow driver instead
of asserting Compose `mutableStateOf` details.

### Create a reusable contract suite

Define a driver interface in test code, for example:

```kotlin
interface SnapshotWorkflowDriver {
    val state: SnapshotContractState
    fun dispatch(intent: SnapshotContractIntent)
}
```

Write scenarios once in an abstract/base contract test or shared scenario helper. During migration,
provide:

- `LegacyPluginSnapshotDriver` or another legacy driver;
- `SharedSnapshotViewModelDriver`.

Both drivers must pass the same scenarios. Normalize only representation differences that are not
part of the product contract. After the new ViewModel owns the workflow and both Views use it, remove
the legacy driver but keep the contract scenarios.

Use [the MVVM behavior matrix](mvvm-behavior-matrix.md) as the scenario inventory. A migration slice
must list its behavior IDs before editing and attach permanent test evidence to them before removing
legacy code.

### Use temporary parity/shadow mode

For selection, refresh, comparison-source choice, and Accept workflows, allow a temporary test or DEV
shadow harness:

```text
intent + controlled port result
        |                     |
        v                     v
legacy driver          shared ViewModel driver
        \                     /
         normalized state/effect comparison
```

- Never let the shadow implementation perform real writes, commands, notifications, or telemetry.
- Feed both sides the same deterministic inputs and fake port results.
- Report the previous normalized state, intent, and exact state/effect difference.
- Keep shadow comparison out of release behavior and remove it with the legacy driver after parity.

### Apply Red-Green-Refactor

For every migration slice:

1. **Characterization green:** run the legacy behavior scenario.
2. **Red:** add the target-contract scenario and confirm failure for the expected missing behavior.
3. **Green:** implement the minimum shared state/intent/transition or port.
4. **Parity green:** run the scenario against legacy and new drivers.
5. **Integrate:** switch one caller or View.
6. **Regression green:** run focused, module, and architecture checks.
7. **Refactor:** remove duplication or improve responsibilities while tests remain green.
8. **Commit-sized checkpoint:** do not start another responsibility until this slice is independently
   passing and reviewable.

For a mechanical move with no new behavior or contract, keep the characterization suite green before
and after the single move. Do not create a test whose only purpose is to fail synthetically.

### Test design rules

- Assert public state, typed outcomes, and effects.
- Use deterministic fake ports and manually completed async requests.
- Inject clocks, dispatchers/executors, IDs, and side-effect ports where nondeterminism exists.
- Never use sleeps to wait for state.
- Test stale completions, cancellation, retry, ambiguity, empty data, I/O failure, and selection
  restoration where relevant.
- Keep View tests limited to rendering state and mapping UI events/effects.
- Keep architecture/package assertions separate from behavior tests.

## 6. Migration slices

### Slice 0 — safety baseline

- Run `:core:test`, `:app:test`, and `:public-plugin:test`.
- Review every row in `docs/mvvm-behavior-matrix.md`; add missing behavior before production moves.
- Inventory behavior currently embedded in `AppState` and `RedesignedScreenshotPanel`.
- Add missing characterization tests before production moves.
- Record known failures separately; do not normalize them into the refactor.

### Slice 1 — module and test harness

- Add `:presentation` with JUnit tests and Java 17 bytecode.
- Add architecture checks for forbidden imports/dependencies and packaging direction.
- Introduce contract-test drivers without changing production behavior.
- Add contract tests for serialized/reentrant intents, state-before-effect ordering, non-replayed
  effects, stale results, and idempotent close.

### Slice 2 — snapshot selection

- Use `PluginSnapshotController` behavior as the starting contract.
- Introduce shared `SnapshotState`, `SnapshotIntent`, and `SnapshotViewModel`.
- Cover stable View/Case/golden IDs, equivalent refresh, missing Case fallback, extension cards,
  filters, editor/host selection, and stale generations.
- Adapt plugin and app selection to the shared contract one host at a time.

### Slice 3 — indexing and refresh

- Move host-neutral refresh orchestration and soft-rescan state.
- Keep PSI, VFS, WatchService, and thread dispatch in adapters.
- Cover structural vs in-place PNG changes, merged variants, previous-state visibility, and stale
  refresh completion.

### Slice 4 — comparison

- Move comparison selection and load state/effects.
- Keep image conversion and rendering framework-specific.
- Cover Working/Test fallback, no-HEAD/new/missing states, mode persistence, and stale image loads.

### Slice 5 — Accept

- Move Accept/Accept-all presentation workflow.
- Reuse `GoldenAccept` from `:core`; inject the execution port when orchestration requires it.
- Cover confirmation, progress, partial failure, retry subset, ambiguity, stale source, and return to
  Working-copy state.

### Slice 6 — commands and notifications

- Share command planning and presentation state.
- Keep IntelliJ Run execution and desktop process execution as separate adapters.
- Cover deduplication, sequential outcomes, cancellation, refresh suppression, completion status, and
  notification effects.

### Slice 7 — settings

- Share editing, validation, and change-impact decisions.
- Keep IntelliJ persistence and app config persistence separate.
- Cover command-only changes, association-changing settings, cache invalidation, and watcher restart
  decisions.

### Slice 8 — remove god objects

- Reduce `AppState` to app-shell responsibilities or replace it with feature ViewModels plus a small
  coordinator.
- Reduce `RedesignedScreenshotPanel` to Swing construction, rendering, event mapping, and adapter
  wiring.
- Remove legacy workflow paths only after both host suites and shared contracts pass.

## 7. Validation gates

At every slice, run the smallest focused test first. Before declaring the slice complete, run:

```bash
./gradlew :presentation:test :core:test --console=plain
./gradlew :app:test :public-plugin:test --console=plain
.agents/skills/golden-diff-architecture/scripts/check-architecture.sh
```

Run only commands for modules that exist at the current migration stage. After dependency or
packaging changes, also build both plugin ZIPs and audit that:

- `core.jar` and the shared presentation JAR occur only where intended;
- the internal plugin contains no duplicate shared classes;
- neither ZIP contains Compose, Jewel, or Skiko;
- plugin/shared bytecode remains Java 17.

Once `:presentation` exists, run `:presentation:test` on Linux CI as well as the macOS full build.
This is a required portability gate, not an optional platform matrix.

Read every Gradle log for `BUILD SUCCESSFUL` or `BUILD FAILED`. Never run Plugin Verifier locally.

## 8. Completion criteria

The migration is complete when:

- both Views render the same shared workflow state and dispatch the same semantic intents;
- domain and presentation behavior is implemented once;
- platform differences exist only in Views, composition roots, and explicit adapters;
- shared ViewModels have deterministic contract tests independent of UI frameworks;
- every behavior-matrix row has permanent shared-contract or host-adapter evidence;
- effect, lifecycle, concurrency, and state invariants have dedicated tests;
- legacy `AppState`/panel workflow logic and temporary parity drivers are removed;
- host View tests cover only framework adaptation;
- all shared, core, app, plugin, architecture, packaging, and bytecode gates pass.
