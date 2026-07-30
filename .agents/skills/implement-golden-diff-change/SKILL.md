---
name: implement-golden-diff-change
description: Implement Golden Diff features and bug fixes with regression tests, MVVM separation, OOP/SOLID design, correct module ownership, safe async state, and proportional verification. Use when adding or changing production behavior in :core, :core-ui, :telemetry, :public-plugin, :internal-plugin, or :app.
---

# Implement a Golden Diff change

## Establish constraints

1. Read `../golden-diff-architecture/SKILL.md` and every project/reference file it requires.
2. Inspect the affected behavior, neighboring implementation, tests, and Gradle dependencies.
3. Write down the acceptance behavior, preserved invariants, failure modes, and affected modules.
4. Choose ownership using the architecture contract before adding types or dependencies.

## Design the change

- Model new behavior as explicit domain outcomes and presentation state.
- Keep the View limited to rendering state and sending intents.
- Put workflow transitions in a cohesive ViewModel/controller; inject side-effect collaborators.
- Extract a `:core` policy/use case only when semantics are host-neutral.
- Keep host APIs in adapters and wire them at the host boundary.
- Use a small interface only for genuine variability, inversion, or deterministic testing.
- Extend existing contracts compatibly when the internal plugin or persisted settings may consume them.
- Define asynchronous cancellation, generation/identity checks, and UI-thread publication before launching work.

Prefer the smallest complete vertical slice. Avoid opportunistic rewrites outside the behavior being added.

## Build regression protection

1. Add a focused failing test for a bug or the smallest behavior test for a feature.
2. Test pure rules and transitions below the UI toolkit.
3. Cover relevant success, empty, invalid, failure, cancellation, retry, ambiguity, and stale-result paths.
4. Add an architecture/artifact assertion when the change affects dependencies, packaging, bytecode, or extension contracts.
5. Keep tests deterministic: inject clocks/executors/ports, use stable IDs, and avoid timing sleeps.

## Implement

- Keep each edit aligned with one responsibility.
- Reuse established models, indexes, extension points, and host adapters.
- Preserve public and persisted contracts unless the request explicitly changes them.
- Do not duplicate domain decisions in Swing and Compose.
- Do not add a dependency to solve a problem already handled by the JDK or existing modules without documenting the tradeoff.

## Validate

1. Run the closest tests during the edit.
2. Run `.agents/skills/golden-diff-architecture/scripts/check-architecture.sh`.
3. Run `:core:test` for shared logic/contracts and each affected module test suite.
4. Build and audit plugin ZIPs for build/dependency/public-plugin API changes.
5. Confirm the Gradle log contains `BUILD SUCCESSFUL`; never run Plugin Verifier locally.

Finish by reporting changed behavior, architecture placement, regression tests, commands run, and any residual risk.
