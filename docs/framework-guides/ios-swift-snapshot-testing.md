# Framework guide: iOS · swift-snapshot-testing (pointfree)

Read this when the project's screenshot tests use **pointfreeco/swift-snapshot-testing**. It states
exactly what to set on the **project side** and the **GoldenDiff side** so the two work together.

Official documentation: <https://pointfreeco.github.io/swift-snapshot-testing/> (source:
<https://github.com/pointfreeco/swift-snapshot-testing>).
**If anything below is unclear or the project deviates from these examples, verify against the project's
own build/test config (read-only) and the official docs above before configuring — do not guess.**

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

GoldenDiff **can set this inline in its configured Generate/Verify commands** (see below), which is
preferred because it makes the buttons self-contained and requires no shell-profile change. External
test runs still need the exported variable; inspect the existing shell/CI environment read-only and,
if it is absent, tell the user the exact `export` line without applying it. Do not edit a shell
profile, CI file, Xcode project, scheme, or test plan as part of Golden Diff setup.

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

## GoldenDiff side — commands (examples)
Configure the command templates so the tool-window Generate/Verify buttons work. XCTest snapshot tests
run through the normal test flow, so **Verify** is just running the tests; scope a single file with
`-only-testing:<Target>/<Class>`.

The standalone method tree can scope a separate generator file and method. When test target names are
encoded by the first segment after `Tests/`, derive the target from `{snapshot_file_path}` and use:

```bash
# View refresh (the app runs this once per unique generator file):
TEST_PATH='{snapshot_file_path}'; TARGET="${TEST_PATH#*Tests/}"; TARGET="${TARGET%%/*}"; \
xcodebuild test … -only-testing:"$TARGET/{snapshot_class_name}"

# Case refresh:
TEST_PATH='{snapshot_file_path}'; TARGET="${TEST_PATH#*Tests/}"; TARGET="${TARGET%%/*}"; \
xcodebuild test … -only-testing:"$TARGET/{snapshot_class_name}/{snapshot_method_name}"
```

Only use this form when the inspected project layout really encodes the target that way.

Resolve the test destination with the user; **never copy a device model from a guide example or
choose one silently**:

1. Inspect the project's Fastlane lane, CI command, test script and schemes to identify the plausible
   scheme and test target(s). Run the read-only `xcrun simctl list devices available` to collect the
   simulator destinations that are actually installed.
2. Ask the user which test target/destination to use. Present the detected scheme/test target and a
   concise list of available simulator + runtime choices; when project configuration already names a
   valid destination, mark it as the recommendation but still ask for confirmation.
3. After the user answers, run the non-building
   `xcodebuild -showdestinations -scheme <scheme>` and match the selected device in its **Available
   destinations**. Use the exact per-device `OS:` value printed there. The broad runtime heading from
   `simctl` can omit a patch component (for example `26.3` while xcodebuild requires `26.3.1`);
   `xcodebuild -showdestinations` is authoritative for destination syntax.
4. Do not save the Generate/Verify commands until the user answers and that validation succeeds.
   Store the chosen stable device name + exact OS version, not a simulator UUID. Never save a literal
   placeholder or guess a popular model.

### Non-interactive package plugin and macro trust

An `xcodebuild` process started outside Xcode may stop before compilation when a package build-tool
plugin or Swift macro has not yet been approved in that environment. Inspect Fastlane, CI, scripts
and any checked-in package-plugin approval data read-only before changing the command. Recognize these
specific failures:

- `Plugin “…” from package “…” must be enabled before it can be used` — the command may need
  `-skipPackagePluginValidation`.
- `Macro “…” from package “…” must be enabled before it can be used` — the command may need
  `-skipMacroValidation`.

These flags disable Xcode trust checks and therefore carry a supply-chain risk. Do not add them
preemptively merely because the project uses plugins or macros. Suggest them when the project already
uses them in its non-interactive test setup, or after the user reports the corresponding validation
failure for dependencies they trust. Confirm the installed Xcode supports each proposed flag with the
read-only `xcodebuild -help`.

When needed, add the applicable flag(s) consistently to **all four** Generate/Verify templates, for
example:
```
xcodebuild test -scheme <scheme> -destination "platform=iOS Simulator,name=<device>,OS=<runtime>" -skipPackagePluginValidation -skipMacroValidation
```
Replace every `<…>` value before saving. This affects only non-interactive trust validation; it does
not replace `TEST_RUNNER_SNAPSHOT_ARTIFACTS`, the Generate-only recording variable, or per-file
`-only-testing`.

First inspect the resolved `swift-snapshot-testing` version in `Package.resolved`:

- **1.17.0 or newer:** the library officially reads `SNAPSHOT_TESTING_RECORD`; use
  `TEST_RUNNER_SNAPSHOT_TESTING_RECORD=all` so `xcodebuild` forwards it into the simulator test runner.
  This is the Generate switch — no wrapper and no source-code change are needed.
- **Older than 1.17.0:** the environment switch is unavailable. Configure Generate only when the
  project already has a verified lane/script or its own environment-driven recording hook; otherwise
  leave Generate unset and explain that upgrading to 1.17+ enables a command-only setup.

Command shape for 1.17+ (every `<…>` value must be replaced with a project value or destination
confirmed by the user; preserve `$PWD` literally so it expands when GoldenDiff runs it):
```
--generate-all-cmd  'TEST_RUNNER_SNAPSHOT_TESTING_RECORD=all TEST_RUNNER_SNAPSHOT_ARTIFACTS="$PWD/<generated-dir>" xcodebuild test -scheme <scheme> -destination "platform=iOS Simulator,name=<available-device>,OS=<installed-runtime>"'
--generate-file-cmd 'TEST_RUNNER_SNAPSHOT_TESTING_RECORD=all TEST_RUNNER_SNAPSHOT_ARTIFACTS="$PWD/<generated-dir>" xcodebuild test -scheme <scheme> -destination "platform=iOS Simulator,name=<available-device>,OS=<installed-runtime>" -only-testing:<test-target>/{class_name}'
--verify-all-cmd    'TEST_RUNNER_SNAPSHOT_ARTIFACTS="$PWD/<generated-dir>" xcodebuild test -scheme <scheme> -destination "platform=iOS Simulator,name=<available-device>,OS=<installed-runtime>"'
--verify-file-cmd   'TEST_RUNNER_SNAPSHOT_ARTIFACTS="$PWD/<generated-dir>" xcodebuild test -scheme <scheme> -destination "platform=iOS Simulator,name=<available-device>,OS=<installed-runtime>" -only-testing:<test-target>/{class_name}'
--verify-case-cmd   'TEST_RUNNER_SNAPSHOT_ARTIFACTS="$PWD/<generated-dir>" xcodebuild test -scheme <scheme> -destination "platform=iOS Simulator,name=<available-device>,OS=<installed-runtime>" -only-testing:<test-target>/{snapshot_class_name}/{snapshot_method_name}'
```

Generate and Verify intentionally share the same underlying test command; Generate differs by the
recording environment variable. If the project already has a reliable `xcodebuild` wrapper/lane, keep
that command and prefix it with the same `TEST_RUNNER_…` variables rather than inventing another test
invocation. Do not run either command during automated configuration.

Before saving, audit the four commands together:

- all four use the user-confirmed scheme and exact destination from `xcodebuild -showdestinations`;
- all four set `TEST_RUNNER_SNAPSHOT_ARTIFACTS` to the directory configured by `--generated-dir`;
- only Generate All/File set `TEST_RUNNER_SNAPSHOT_TESTING_RECORD=all`;
- only Generate File/Verify File use `-only-testing:` for file scope;
- only per-file/per-case commands use `-only-testing`; a case ends in
  `/{snapshot_class_name}/{snapshot_method_name}`;
- any required `-skipPackagePluginValidation` / `-skipMacroValidation` flags are present consistently
  in all four commands and were justified by the project's setup or a matching validation failure;
- none contains an unresolved `<…>` placeholder, example device, stale OS value or simulator UUID.

Run `Golden Diff show --json` after configuring and repeat this audit against the persisted values.

## Verify
Do not change a view or run a writing snapshot test as part of automated configuration. Tell the user
that they can later change a view so a snapshot differs, run its snapshot test, and confirm files
appear at `<dir>/<TestClass>/<name>.png`. They can then open the source in GoldenDiff and switch the
compare source to "generated". If files land in `…/CoreSimulator/…/tmp/…` instead, the env var did not
reach the runner — recheck the `TEST_RUNNER_` prefix.
