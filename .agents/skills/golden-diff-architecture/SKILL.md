---
name: golden-diff-architecture
description: Protect Golden Diff architecture, module boundaries, MVVM state design, OOP/SOLID responsibilities, dependency direction, and testability. Use when planning or reviewing architecture, deciding where code belongs, introducing a ViewModel/controller/use case/port/adapter, moving code between modules, changing Gradle dependencies, or checking whether an implementation or refactor preserves the core modules.
---

# Golden Diff architecture

Use this skill as the mandatory architecture contract for implementation and refactoring work.

## Load project context

1. Read `AGENTS.md`.
2. Read `docs/architecture.md`, `docs/features.md`, and `docs/gotchas.md`.
3. Read `docs/architecture-guidelines.md` in full.
4. Read `docs/build-and-run.md`, `docs/roadmap.md`, and `docs/standalone-app-handoff.md` when the task touches build configuration, packaging, compatibility, releases, or the desktop app.
5. Inspect the current Gradle files and neighboring production/tests. Treat code and build files as the source of truth when documentation is stale; report meaningful drift.

## Make the architecture decision first

Before editing code:

1. State the behavior or responsibility being changed.
2. Identify the owning layer and module.
3. List inputs, outputs, state, side effects, and platform APIs.
4. Define the dependency direction and the seam used for I/O or host integration.
5. Identify behavior and architecture tests that will prevent regression.

Do not start implementation while ownership is ambiguous.

## Enforce the design

- Keep domain rules, deterministic transformations, state transitions, and host-neutral contracts independent of UI toolkits.
- Keep Swing, IntelliJ, Compose, operating-system integration, and vendor SDK details at host or adapter boundaries.
- Model presentation as unidirectional MVVM: immutable observable state, explicit user intents, explicit one-shot effects, and injected side-effect collaborators.
- Keep composables and Swing components declarative/thin. Never store widgets, `TreePath`, list indexes, or component instances as durable application state.
- Share behavior and contracts between hosts, not UI frameworks or host lifecycle objects.
- Apply SOLID to create cohesive responsibilities and stable boundaries. Do not introduce interfaces, factories, repositories, or base classes without a real substitution, volatility, or test seam.
- Prefer composition and small collaborators over inheritance and god objects.
- Preserve stable identities, stale-result rejection, selection behavior, and thread ownership in asynchronous workflows.

## Verify

Run:

```bash
.agents/skills/golden-diff-architecture/scripts/check-architecture.sh
```

Then run the smallest relevant test suite and expand according to the changed dependency surface. Never run the IntelliJ Plugin Verifier locally.

Reject a change when a forbidden dependency, untested behavior change, mixed responsibility, or unexplained architecture exception remains.
