# Snapshot method tree plan

## Goal

Change the standalone Files pane from a flat list of matching source files into a three-level
navigation tree:

```text
folder
└── view file
    ├── iOS snapshot test method
    └── Android @Preview function
```

The view file is the single best parent for a set of goldens. Snapshot cases are the functions that
generated those goldens. The model and matching stay framework- and language-neutral; Swift snapshot
tests and Kotlin Compose previews are regression fixtures, not separate product modes.

## Core index and matching

- Extend `SnapshotSourceFile` with `cases: List<SnapshotCase>`.
- A `SnapshotCase` stores a stable ID, method name, generator path, class name, matched goldens,
  aggregate status, and failure count.
- Add a pure `GoldenFinder` matching-evidence API while preserving the public behavior of `find()`.
  It uses the same whole-word and camel-case boundaries as normal annotated-method matching.
- Associate extracted functions with goldens for Swift `func test…`, Kotlin functions carrying a
  matching annotation such as `@Preview`, and the already supported generic functions/tests/stories.
- Choose exactly one view parent for each case:
  1. a non-test file containing the generator function;
  2. the most specific non-test file/class sharing its goldens;
  3. the test/generator file as fallback.
- Detect test containers using function names beginning with `test`, `test`/`tests`/`__tests__` path
  segments, and `Test`, `Tests`, `SnapshotTest(s)`, or `Spec(s)` suffixes.
- Rank competing parents by golden owner agreement, golden coverage, longest matching name, then
  stable path order.
- Keep unmatched goldens on the parent without inventing a child. Aggregate parent status from cases
  and unmatched goldens, and keep directory failure counters view-based.
- Preserve coverage while avoiding loosely matched model/type files as separate top-level rows.

## Standalone navigation

- Project `Folder → View file → Snapshot case`; view rows are collapsed by default.
- Selecting a view shows all of its goldens. Selecting a case shows only that case's goldens.
- Search matches view paths and case names, revealing ancestors of matching cases.
- Failed-only retains failed parents, failed cases, and failed unmatched goldens on the parent.
- Golden selection chooses the most specific case, expands its parent, and scrolls it into view;
  otherwise it selects the parent.
- Preserve selection using `viewPath + generatorPath + methodName`. Keep expansion session-local.
  Persisted open files continue to use the parent path.

## Cache and invalidation

- Build the source/function association index once per project and association fingerprint. Selecting
  another view or case reads its already-associated golden paths instead of parsing the source and
  walking every golden directory again.
- Cache merged results per stable view/case selection and decoded comparisons/thumbnails by file
  modification time.
- For ordinary PNG modifications, refresh only affected visible items and the cached index statuses.
  Verify completion and accepting a golden also refresh statuses without rebuilding associations.
- Rebuild the full snapshot index only for structural golden changes, Generate completion, or changes
  to golden paths and matching rules. Command-only configuration changes do not scan; presentation
  changes invalidate only decoded images.

## Verify commands

- Keep `Verify current file` and add optional `Verify current case` to config, Settings, CLI, and
  persistence.
- Add `{snapshot_file_name}`, `{snapshot_class_name}`, `{snapshot_file_path}`, and
  `{snapshot_method_name}`. Existing `{file_name}`, `{class_name}`, and `{file_path}` continue to
  describe the visible parent.
- Refreshing a view runs `Verify current file` once per unique generator file with duplicate expanded
  commands removed.
- Refreshing a case runs `Verify current case`; when absent, it falls back to `Verify current file`
  for the generator and communicates that the entire generator file is in scope.
- Run batches sequentially, continue after test failures, support cancellation, and show a shared log
  plus aggregate result.
- Document Swift target/class/method and Roborazzi parameterized-preview command examples.

## Verification

- Add core fixtures for Android previews, iOS view/test separation, deterministic parent ranking,
  fallbacks, unmatched goldens, multiple generators, aggregation, de-duplication, and placeholders.
- Add app tests for projection, collapse/search/failed-only behavior, view/case selection, golden
  reveal, view/case refresh, fallback, batch behavior, and cancellation.
- Run `./gradlew :core:test :app:test` and confirm the Gradle log says `BUILD SUCCESSFUL`.
- Audit representative iOS and Android projects when their paths are available, ensuring every golden
  appears exactly once and smoke-test one Swift method and one Roborazzi preview case command.
