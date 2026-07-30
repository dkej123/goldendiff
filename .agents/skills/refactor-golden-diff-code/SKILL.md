---
name: refactor-golden-diff-code
description: Refactor Golden Diff Kotlin code safely while preserving behavior, preventing regressions, improving MVVM boundaries, OOP/SOLID responsibilities, testability, concurrency safety, and Gradle module purity. Use for cleanup, extraction, splitting god classes or state holders, moving code between modules, dependency inversion, presentation-state redesign, or architecture migrations.
---

# Refactor Golden Diff code

Treat refactoring as behavior-preserving unless the user explicitly requests a behavior change.

## Establish the safety net

1. Read `../golden-diff-architecture/SKILL.md` and every project/reference file it requires.
2. Inspect the complete target, its callers, tests, public/extension contracts, persistence, threading, and build dependencies.
3. Record observable behavior and invariants: state, ordering, selection, errors, cancellation, identities, side effects, artifact contents, and compatibility.
4. Run the closest existing tests before editing.
5. Add characterization tests before moving logic when an important behavior is not covered.

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

## Refactor in safe stages

1. Separate pure calculations/transitions from side effects without changing call sites.
2. Introduce typed state, intent, result, or a narrow port at the existing seam.
3. Move one cohesive responsibility and keep adapters at the old boundary temporarily if needed.
4. Switch callers and tests to the new path.
5. Remove obsolete delegation, duplicated state, dead abstractions, and compatibility shims that have no consumer.
6. Run focused tests after every stage that can compile independently.

Keep behavior changes in a separate edit and test them explicitly.

## MVVM migration rules

- Extract screen/workflow state from composables or Swing widgets into an immutable state model.
- Replace direct field mutation with named intents/transitions.
- Separate durable state from one-shot effects and ephemeral rendering state.
- Inject I/O and platform services; do not move them into the ViewModel under a new name.
- Split a god state holder by cohesive workflow and lifecycle. Keep a small coordinator only when cross-workflow coordination is itself one responsibility.
- Preserve stable IDs, selection restoration, soft-refresh behavior, and request generations.
- Do not attempt to share Swing and Compose ViewModels when their lifecycle/presentation semantics differ; share host-neutral models, policies, and use cases.

## SOLID review

- **SRP:** Can each class be described without “and” across unrelated concerns?
- **OCP:** Does variation use composition or the existing extension point?
- **LSP:** Do all implementations preserve the same outcomes, threading, and lifecycle contract?
- **ISP:** Does each consumer depend only on capabilities it uses?
- **DIP:** Does policy depend on a stable owned contract with host/vendor code outside?

Prefer composition. Avoid base classes, service locators, generic managers, broad repositories, and callback graphs with hidden mutation.

## Regression gates

1. Run focused characterization/unit tests.
2. Run `.agents/skills/golden-diff-architecture/scripts/check-architecture.sh`.
3. Run `:core:test` when shared code/contracts move or change.
4. Run every affected host/module test suite.
5. Build/audit plugin ZIPs for module/dependency/API moves.
6. Compare pre/post behavior, public signatures, persisted data, and artifacts relevant to the refactor.
7. Confirm `BUILD SUCCESSFUL`; never run Plugin Verifier locally.

Stop and report instead of completing the refactor if preserving behavior would require guessing an undocumented contract, if tests expose conflicting behavior, or if the desired boundary needs a new product decision.
