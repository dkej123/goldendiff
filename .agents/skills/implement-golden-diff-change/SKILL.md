---
name: implement-golden-diff-change
description: Implement Golden Diff features and bug fixes with test-driven development, regression tests, MVVM separation, OOP/SOLID design, correct module ownership, safe async state, and proportional verification. Use when adding or changing production behavior in :core, :core-ui, :telemetry, :public-plugin, :internal-plugin, :app, or a shared presentation module.
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

## Work test-first

Use one Red-Green-Refactor cycle per observable behavior:

1. **Red:** add the smallest test that expresses the requested outcome through a public state, result, or effect. Run it and confirm it fails for the expected reason.
2. **Green:** implement only enough production code to pass that test.
3. **Safety:** rerun the focused test plus the closest existing regression suite.
4. **Refactor:** improve names, responsibilities, and duplication while keeping all tests green.
5. **Checkpoint:** run the architecture check and record the exact successful commands before starting another behavior.

For a bug, make the test fail because of the original cause. Test pure rules and presentation transitions below the UI toolkit. Cover relevant success, empty, invalid, failure, cancellation, retry, ambiguity, and stale-result paths. Inject clocks/executors/ports, use stable IDs, and avoid timing sleeps.

For a purely mechanical move or rename, do not invent a meaningless red test. Establish a green characterization baseline, perform one mechanical step, and rerun the same tests immediately.

## Implement

- Keep each edit aligned with one responsibility.
- Reuse established models, indexes, extension points, and host adapters.
- Preserve public and persisted contracts unless the request explicitly changes them.
- Do not duplicate domain decisions in Swing and Compose.
- Do not add a dependency to solve a problem already handled by the JDK or existing modules without documenting the tradeoff.

## Validate

1. Run the focused test at every Red-Green-Refactor checkpoint.
2. Run `.agents/skills/golden-diff-architecture/scripts/check-architecture.sh`.
3. Run `:core:test` for shared logic/contracts and each affected module test suite.
4. Build and audit plugin ZIPs for build/dependency/public-plugin API changes.
5. Confirm the Gradle log contains `BUILD SUCCESSFUL`; never run Plugin Verifier locally.

Finish by reporting changed behavior, architecture placement, regression tests, commands run, and any residual risk.
