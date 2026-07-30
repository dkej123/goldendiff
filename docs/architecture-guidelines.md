# Architecture & responsibility guidelines

This document defines where Golden Diff code belongs and how changes should preserve testability,
module purity, and user-visible behavior. Use it for implementation, refactoring, and architecture
review. `docs/architecture.md` describes the current system; this document is the prescriptive
contract.

## Contents

1. Architectural goals
2. Layers and dependency flow
3. Module ownership
4. MVVM responsibilities
5. OOP and SOLID rules
6. Concurrency and state
7. TDD and regression protection
8. Decision checklist

## 1. Architectural goals

Golden Diff has two presentation hosts: a Swing/IntelliJ plugin and a Compose desktop application.
They share domain behavior, not UI frameworks or host lifecycle objects.

Every change should aim for:

- host-neutral domain rules that are cheap to test;
- thin Views that render state and emit user intents;
- cohesive presentation state holders with explicit state transitions;
- platform and vendor APIs isolated in adapters;
- dependencies pointing inward toward stable contracts;
- tests at the lowest layer that fully expresses the behavior;
- small changes whose regression surface is understood.

MVVM is a responsibility boundary, not a naming convention. SOLID is used to improve cohesion,
substitutability, and dependency direction, not to maximize the number of interfaces or classes.

## 2. Layers and dependency flow

Use these conceptual layers:

```text
View (Swing / Compose)
        |
        v
ViewModel or presentation controller
        |
        v
Use cases / domain policies / models
        ^
        |
Ports <--- host and infrastructure adapters
```

The View depends on presentation state. Presentation orchestration depends on host-neutral behavior
and narrow capabilities. Adapters implement those capabilities using IntelliJ, the filesystem, git,
network clients, operating-system APIs, or other frameworks.

Current module direction:

```text
internal-plugin -> public-plugin -> core
                              \-> telemetry
app -> core-ui -> core
app -> core
app -> telemetry
```

Do not force identical presentation architecture onto Swing and Compose. Share models, policies, use
cases, and semantic contracts when both hosts need them; keep lifecycle, scheduling, effects, and
rendering host-specific.

## 3. Module ownership

### `:core`

Owns:

- tool-agnostic domain rules and typed outcomes;
- golden matching, change scanning, indexes, image math, and configuration models;
- pure state projections and reusable use cases;
- filesystem/git/process implementations that use only JDK APIs;
- ports whose semantics are owned by the domain.

Must not own:

- IntelliJ Platform or PSI/VFS types;
- Swing, Compose, Skiko, or Jewel types;
- host lifecycle, dialogs, notifications, UI effects, or widget state;
- vendor SDK types.

`:core` is not a dumping ground for every reusable class. Code belongs there only when its meaning
and API are host-neutral. JDK AWT image primitives such as `BufferedImage` and `ImageIO` are allowed;
Swing widgets are not.

Keep production dependencies limited to JDK APIs plus `compileOnly(kotlin("stdlib"))`. Tests may use
JUnit. When a new external library appears necessary, first check whether the JDK or existing code is
sufficient; otherwise place it in the outermost module that needs it.

### `:core-ui`

Owns:

- app-only Compose comparison canvases;
- visual adaptation of `:core` image and geometry models.

Must not own:

- application workflow state, persistence, or filesystem/network orchestration;
- IntelliJ or Swing integration;
- Compose runtime packaging.

Keep `:core` and Compose dependencies `compileOnly`; `:app` supplies the runtime.

### `:telemetry`

Owns:

- cross-host telemetry events and privacy/sanitization rules;
- vendor telemetry backends;
- host-neutral telemetry client behavior.

Must not own:

- feature screen state or domain decisions;
- Swing, Compose, IntelliJ, or host lifecycle behavior.

Host modules decide when a UI/lifecycle event occurred and translate it to the shared telemetry
contract.

### `:public-plugin`

Owns:

- Swing Views and IntelliJ tool-window/settings integration;
- plugin ViewModels or presentation controllers;
- PSI, VFS, Run tool window, project settings, and EDT/background adapters;
- the public `comparisonSource` extension seam.

Must not own:

- Compose, Jewel, Skiko, or app UI;
- domain rules duplicated from `:core`;
- Figma-specific behavior beyond the public extension contract.

Keep `implementation(project(":core"))`. The public plugin must package `core.jar`.

### `:internal-plugin`

Owns:

- Figma comparison-source implementation;
- Figma download, cache, token, and settings adapters;
- registration against the public plugin extension point.

Must not own:

- general Golden Diff behavior;
- a second copy of `:core`;
- Compose UI.

Keep `compileOnly(project(":core"))`. At runtime, core types resolve through the public plugin
classloader.

### `:app`

Owns:

- Compose desktop Views and app presentation state;
- app lifecycle, settings persistence, CLI, WatchService, and macOS adapters;
- composition-root wiring and the Compose runtime.

Must not own:

- IntelliJ or Swing code;
- domain policies duplicated from `:core`;
- Figma integration.

## 4. MVVM responsibilities

### Model

- Represent domain concepts with explicit data classes, enums, value types, and sealed outcomes.
- Keep invariants close to the model or pure use case.
- Prefer typed Missing/Exact/Ambiguous and Success/Failure outcomes over sentinel strings.
- Keep models free of Swing, Compose, IntelliJ, and vendor SDK types.

### ViewModel or presentation controller

- Own one cohesive screen or workflow state and its transitions.
- Expose immutable state snapshots or a read-only observable stream.
- Accept named user/system intents; do not let the View mutate state fields directly.
- Invoke injected use cases and side-effect capabilities.
- Represent durable UI state separately from one-shot effects such as opening a dialog, showing a
  notification, focusing a tool window, or starting a command.
- Make derived state pure and deterministic.
- Define lifecycle, cancellation, thread publication, and stale-result rejection.
- Split when unrelated workflows cause the state holder to change for unrelated reasons.

`PluginSnapshotController` is the local pattern for typed Swing-independent presentation state.
`AppState` is an existing Compose state holder and a migration source, not permission to keep adding
unrelated responsibilities. Prefer extracting cohesive workflow state and collaborators rather than
growing a god ViewModel.

### View

- Render state and forward intents.
- Keep only ephemeral rendering state locally: hover, focus, animation progress, measurement, or an
  uncommitted text draft.
- Do not perform domain decisions, filesystem/git/network access, or long-running work.
- Do not coordinate multiple services directly from composables or Swing listeners.
- Do not store widgets, `TreePath`, list indexes, or component instances as durable state.

### Use case

Introduce a named use case when an operation coordinates domain rules or several ports and represents
a meaningful user/system operation. Keep pure policies and transitions separate from side effects
when this makes behavior deterministic and reusable.

Do not create a use-case class for a trivial one-line delegation that has no policy, coordination, or
test value.

### Port and adapter

Create a port when high-level behavior owns a stable capability and at least one of these is true:

- the implementation is platform/vendor-specific;
- deterministic tests need substitution;
- multiple implementations already exist or are expected by an established extension mechanism;
- resource ownership or failure semantics need a clear contract.

Keep ports capability-sized. Wire implementations in the host composition root. Do not wrap every
concrete class in an interface.

## 5. OOP and SOLID rules

### Single responsibility

A class should have one cohesive reason to change. Separate rendering, state transitions,
persistence, I/O, mapping, and policy when they evolve independently. A coordinator may use several
collaborators when coordinating them is its single workflow responsibility.

### Open/closed

Use composition and established extension points for meaningful variation. Team-specific comparison
data belongs in another `comparisonSource` contribution, not a fork of the public plugin or a Figma
branch inside `:core`.

### Liskov substitution

Contracts include behavior, not just signatures. Implementations must preserve success/failure
outcomes, cancellation, ordering, threading, and resource-ownership expectations.

### Interface segregation

Consumers should depend only on capabilities they use. Avoid broad repositories and services that
combine unrelated reads, writes, lifecycle, and UI concerns. Keep additions to the public plugin API
minimal because the internal plugin consumes it across a classloader boundary.

### Dependency inversion

High-level policy owns and depends on the smallest stable semantic contract. Host/vendor code
implements it at the edge. Dependency inversion does not require an interface when there is no
volatile boundary or useful substitute.

### Design restraint

- Prefer composition over inheritance.
- Prefer explicit data flow over globals, service locators, bidirectional binding, and callbacks with
  hidden mutation.
- Avoid speculative abstractions and mixed-responsibility `Base*`, `Manager`, `Helper`, or `Util`
  containers.
- Do not split a cohesive class only to meet a line-count target.
- Do not move code between modules to make packages look symmetric.
- Remove dead indirection after migration; do not retain parallel old/new paths without an explicit
  compatibility need.

## 6. Concurrency and state

Preserve these project-specific invariants:

- Run scanning, git, image decode, filesystem, network, and commands off the UI thread.
- In the plugin, use the established read-action pattern for PSI and publish UI updates on the EDT.
- In the app, update UI state through the composition-owned scope. Do not add `Dispatchers.Main`; it
  fails in the packaged runtime.
- Carry stable IDs and request generations through asynchronous work; discard stale completions.
- Keep matching candidates caret-independent and do not add a caret listener.
- Preserve previous usable state during soft rescans and restore selection by stable identity.
- Refresh merged Working-copy and Test-output variants together.
- Resolve generated counterparts through the shared index and keep ambiguity typed.
- Freeze and validate the generated source identity before Accept writes.

## 7. TDD and regression protection

Before implementation or refactoring:

1. List observable behavior, public contracts, persisted formats, threading, ordering, failure modes,
   and artifact constraints that may change.
2. Find existing tests that protect them.
3. Add characterization tests first when important current behavior is uncovered.

Use a Red-Green-Refactor checkpoint for each observable behavior:

1. **Red:** write a focused test through public state, outcome, or effect and confirm the expected
   failure.
2. **Green:** add only enough production code to pass.
3. **Safety:** rerun the focused test and closest existing regression suite.
4. **Refactor:** improve the structure without changing behavior.
5. **Checkpoint:** run the architecture check and record successful commands before taking the next
   slice.

Behavior-preserving refactors start with a green characterization baseline. When introducing a new
contract or shared ViewModel, add a target-contract test that is red until the new seam exists. For a
pure rename or mechanical move, do not invent a meaningless failure: perform one move and immediately
rerun the characterization suite.

During migration, prefer one reusable behavior-contract suite with two drivers:

- a temporary driver over the legacy controller/state holder;
- a driver over the new shared implementation.

Run identical scenarios against both and compare normalized public state and effects. Do not compare
private fields, framework state containers, or incidental collaborator call order. Remove the legacy
driver after cutover; retain the contract suite permanently against the new implementation.

Test at the cheapest correct layer:

- pure models, policies, reducers/transitions, parsers, matching, and use cases in `:core`;
- shared ViewModel/controller transitions without Compose, Swing, IntelliJ fixtures, real time, or
  real I/O;
- host adapters in their owning module;
- Views only for state rendering and intent/effect adaptation.

Cover relevant success, empty, invalid, failure, cancellation, retry, ambiguity, stale completion, and
identity-preservation paths. Use fakes at owned ports. Assert public state and outcomes rather than
private implementation call order unless ordering is itself the contract.

For a bug, add a focused test that fails because of the original cause.

Validation ladder:

1. Run the closest test class or module.
2. Run `:core:test` whenever shared logic or contracts change.
3. Run every directly affected host/module test suite.
4. Build affected plugin ZIPs after dependency, build, or public-API changes.
5. Audit ZIP contents and Java 17 bytecode when module boundaries or packaging change.
6. Run app packaging or smoke checks only when runtime packaging is in scope.

Always read Gradle output for `BUILD SUCCESSFUL` or `BUILD FAILED`. Never run `verifyPlugin`,
`runPluginVerifier`, or the Plugin Verifier CLI locally.

## 8. Decision checklist

Before approving a design or diff, answer:

- What user-visible or system behavior is preserved or changed?
- Which module owns the rule, and why?
- Is each new type a domain model, presentation state, use case, port, adapter, or View?
- Can the core API be used without IntelliJ, Swing, Compose, or vendor SDKs?
- Does the View only render state and emit intents?
- Can transitions be tested without a UI toolkit, filesystem, or network?
- Does every abstraction have a real consumer, variation, volatility, or test seam?
- Are lifecycle, cancellation, stale results, and stable IDs defined?
- What regression test fails if the old bug or dependency leak returns?
- What validation proves both behavior and artifact integrity?

If ownership or invariants remain unclear, reduce scope or redesign before editing.
