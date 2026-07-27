# Framework guide: iOS · swift-snapshot-testing (pointfree)

Read this when the project's screenshot tests use **pointfreeco/swift-snapshot-testing**. It states
exactly what to set on the **project side** and the **GoldenDiff side** so the two work together.

## Detect this framework if ANY holds
- `Package.resolved` / `Package.swift` references `pointfreeco/swift-snapshot-testing`.
- Test sources `import SnapshotTesting` or call `assertSnapshot(...)`.
- Committed reference images live under `__Snapshots__/<TestClass>/<name>.png`.

## How the framework behaves (why the setup below is needed)
On a **failing** snapshot the library writes the freshly rendered image to
`$SNAPSHOT_ARTIFACTS/<TestFileName>/<name>.png`. If `SNAPSHOT_ARTIFACTS` is unset it falls back to a
**volatile** simulator temp dir (`…/CoreSimulator/Devices/<UDID>/data/tmp/…`) that no static config
can target. Passing snapshots produce no file — which is fine, GoldenDiff only needs the image when it differs.

## Project side — make verify output land in a stable directory
Do NOT modify the iOS project (no committed scheme / test-plan / source edits), and do NOT ask which
run method to wire — default to `xcodebuild`. Xcode ⌘U is opt-in only (see end).

**Required env var (verified):** `xcodebuild test` forwards **only** `TEST_RUNNER_`-prefixed vars into
the simulator runner (a plain `export`, `SIMCTL_CHILD_`, or a test-plan `$(SRCROOT)` do **not** work).
So generated output reaches GoldenDiff only if this is set for the test run:
```
export TEST_RUNNER_SNAPSHOT_ARTIFACTS="<dir>"   # <dir> absolute, e.g. <repo>/.golden-diff-generated
```

**GoldenDiff cannot set this itself** — this is the one thing to raise with the user. Tell them the
variable must be defined, then detect their shell from `$SHELL` (zsh → `~/.zshrc`, bash →
`~/.bash_profile` or `~/.bashrc`) and **offer to append the `export` line for them**. If they decline,
print the exact line to add themselves (or to set in CI). This is the only decision the guide leaves to the user.

**Xcode ⌘U (GUI) — opt-in only.** Do not wire it unless the user explicitly asks. If they do: they set
`SNAPSHOT_ARTIFACTS=<dir>` in the test plan / scheme (a committed change) or run
`launchctl setenv SNAPSHOT_ARTIFACTS <dir>` (session-scoped, unverified).

## GoldenDiff side — configure to read that directory
```
--golden-dir-regex '.*/__Snapshots__$'      # committed reference roots
--generated-dir    <dir>                     # same absolute dir as SNAPSHOT_ARTIFACTS above
--generated-regex  '^(.+)\.png$'             # NOT the default _actual regex — pointfree keeps the golden's exact name
--match-mode       file-class                # XCTest methods are unannotated; goldens carry the test class/file name
```
Golden `__Snapshots__/<TestClass>/<name>.png` maps to generated `<dir>/<TestClass>/<name>.png` — exact, no `_actual` suffix.

## Verify
Change a view so a snapshot differs, run its snapshot test, confirm files appear at
`<dir>/<TestClass>/<name>.png`, then open the source in GoldenDiff and switch the compare source to
"generated". If files land in `…/CoreSimulator/…/tmp/…` instead, the env var did not reach the
runner — recheck the `TEST_RUNNER_` prefix.
