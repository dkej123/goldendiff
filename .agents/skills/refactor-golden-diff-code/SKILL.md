---
name: refactor-golden-diff-code
description: Refactor Golden Diff Kotlin code safely with test-driven development while preserving behavior, preventing regressions, and improving MVVM boundaries, OOP/SOLID responsibilities, testability, concurrency safety, and Gradle module purity. Use for cleanup, extraction, splitting god classes or state holders, moving code between modules, dependency inversion, shared presentation-state redesign, or architecture migrations.
---

# Refactor Golden Diff code

Treat refactoring as behavior-preserving unless the user explicitly requests a behavior change.

## Establish the safety net

1. Read `../golden-diff-architecture/SKILL.md` and every project/reference file it requires.
2. Inspect the complete target, its callers, tests, public/extension contracts, persistence, threading, and build dependencies.
3. Record observable behavior and invariants: state, ordering, selection, errors, cancellation, identities, side effects, artifact contents, and compatibility.
4. Run the closest existing tests before editing and record the green baseline.
5. Add characterization tests before moving logic when an important behavior is not covered.
6. Express characterization through public state, outcomes, and effects so the same scenario can test the new component.
7. Select the `docs/mvvm-behavior-matrix.md` IDs owned by the slice. Do not mix unrelated IDs in one migration step.

Do not infer correctness from compilation alone.

## Choose a meaningful refactor

Name the responsibility problem and desired boundary. Refactor only when it improves at least one of:

- cohesion or single ownership;
- testability of behavior without UI/filesystem/network;
- dependency direction or module purity;
- explicit state and effects;
- substitution or extension without branching/forking;
- removal of duplication in domain policy;
- lifecycle, cancellation, stale-result, or resource ownership clarity.

Reject changes motivated only by line count, pattern fashion, package symmetry, speculative reuse, or adding interfaces to make the code look SOLID.

## Use TDD for every migration slice

Use this loop:

1. **Characterize:** write or select a test that captures the current observable behavior. Run it against the current implementation and confirm it is green.
2. **Red:** express the same scenario through the intended state/intent/effect or port contract. Run the new target test and confirm it fails for the expected missing behavior. For a mechanical move with no new contract, keep the characterization test green instead of manufacturing a failure.
3. **Green:** add the smallest new component or delegation needed to satisfy the target contract.
4. **Parity:** run the same behavior scenarios against the legacy seam and the new seam while both exist. Compare normalized public state/effects, not private fields or call order.
5. **Refactor:** move one cohesive responsibility, remove duplication, and improve naming while the focused and parity suites stay green.
6. **Integrate:** switch one caller or one View to the new seam.
7. **Regression checkpoint:** run the focused tests, the closest module suite, and the architecture check. Do not begin the next slice until they pass.
8. **Remove legacy:** delete old delegation only after every scenario and affected host test is green on the new path.
9. **Update evidence:** record the permanent contract test and host-adapter tests against the migrated behavior-matrix IDs.

Keep behavior changes in a separate edit and test them explicitly.

Prefer a reusable contract test with a fixture/driver factory over copying assertions. During migration, run the contract once against a legacy driver and once against the new shared ViewModel. After cutover, remove the legacy driver but retain the contract suite.

## MVVM migration rules

- Extract screen/workflow state from composables or Swing widgets into an immutable state model.
- Replace direct field mutation with named intents/transitions.
- Separate durable state from one-shot effects and ephemeral rendering state.
- Inject I/O and platform services; do not move them into the ViewModel under a new name.
- Split a god state holder by cohesive workflow and lifecycle. Keep a small coordinator only when cross-workflow coordination is itself one responsibility.
- Preserve stable IDs, selection restoration, soft-refresh behavior, and request generations.
- Share framework-neutral feature ViewModels when both hosts implement the same workflow semantics. Keep host shell/lifecycle state separate and do not force one ViewModel over genuinely different host behavior.
- Test shared ViewModels without Compose, Swing, IntelliJ fixtures, real threads, filesystem timing, or network.
- Test each View separately only for state rendering and intent/effect adaptation.
- Enforce serialized intent processing, state-before-effect publication, non-replayed UI effects, request identities, and idempotent `close()` according to `docs/mvvm-refactor-plan.md`.

## SOLID review

- **SRP:** Can each class be described without “and” across unrelated concerns?
- **OCP:** Does variation use composition or the existing extension point?
- **LSP:** Do all implementations preserve the same outcomes, threading, and lifecycle contract?
- **ISP:** Does each consumer depend only on capabilities it uses?
- **DIP:** Does policy depend on a stable owned contract with host/vendor code outside?

Prefer composition. Avoid base classes, service locators, generic managers, broad repositories, and callback graphs with hidden mutation.

## Regression gates

1. Run the focused characterization, target-contract, and parity tests after every migration slice.
2. Run `.agents/skills/golden-diff-architecture/scripts/check-architecture.sh`.
3. Run `:core:test` when shared code/contracts move or change.
4. Run every affected host/module test suite.
5. Build/audit plugin ZIPs for module/dependency/API moves.
6. Compare pre/post behavior, public signatures, persisted data, and artifacts relevant to the refactor.
7. Confirm `BUILD SUCCESSFUL`; never run Plugin Verifier locally.

Stop and report instead of completing the refactor if preserving behavior would require guessing an undocumented contract, if tests expose conflicting behavior, or if the desired boundary needs a new product decision.
