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

## How the framework behaves
On a **failing** snapshot the library writes the freshly rendered image to
`$SNAPSHOT_ARTIFACTS/<TestFileName>/<name>.png`. When `SNAPSHOT_ARTIFACTS` is unset it falls back to a
simulator temp dir (`…/CoreSimulator/Devices/<UDID>/data/tmp/…`). The XCTest failure stored in the
latest `.xcresult` contains both the reference URL (`@−`) and actual URL (`@+`). Golden Diff reads
that result bundle, decodes the `file://` URL, and uses the actual PNG while it still exists.
Passing snapshots produce no file — which is fine, Golden Diff only needs the image when it differs.

## Project side — no artifact environment setup required
Do not add `SNAPSHOT_ARTIFACTS` to a Test Plan or Scheme, do not add a generated directory to
`.gitignore`, and do not add a scheme pre-action. Normal Xcode (`⌘U` or a single test) and
`xcodebuild test` runs already produce an `.xcresult` under DerivedData. Golden Diff uses the newest
result associated with the current `.xcodeproj` or `.xcworkspace` and ignores failure records whose
reference URL belongs to another checkout.

## Golden Diff side — configure matching
```
--golden-dir-regex '.*/__Snapshots__$'      # committed reference roots
--generated-source xcresult                  # discover failing actuals through Xcode result bundles
--match-mode       file-class                # XCTest methods are unannotated; goldens carry the test class/file name
```
Leave `--generated-dir` unset. Directory outputs and their filename regex are ignored while the
XCResult source is active; the selected run supplies already-resolved exact snapshot basenames. The
optional `--xcresult-derived-data <Project>-<hash>` override and
`--xcresult-scan-window-hours <n>` setting are available when auto-detection or the default 48-hour
recent-run window does not fit the project.
Golden Diff maps `__Snapshots__/<TestClass>/<name>.png` to the `.xcresult` actual whose parent is
`<TestClass>` and whose filename is `<name>.png` — exact, no `_actual` suffix.

## GoldenDiff side — commands (examples)
Configure the command templates so the tool-window Generate/Verify buttons work. XCTest snapshot tests
run through the normal test flow, so **Verify** is just running the tests; scope a single file with
`-only-testing:<Target>/<Class>`.

No artifact environment variable is needed in these commands. `TEST_RUNNER_SNAPSHOT_TESTING_RECORD=all`
remains Generate-only: it is a shell-provided
recording switch and therefore needs the prefix when passed directly to `xcodebuild`.

The Files → View → Case tree can scope a separate generator file and method in both hosts. When test target names are
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
choose one silently**. The device **model** and **exact OS runtime version** are both required —
neither is a project setting (nothing in `.xcscheme`/`.xctestplan`/git stores it), so both live only
in the environment that actually ran the tests. A model name alone (e.g. "iPhone 16") is not a valid
destination: the same model can have several runtime versions installed, and a mismatch there is
enough to make swift-snapshot-testing produce visually-identical but byte-different PNGs.

1. Look for ground truth of what Xcode itself last used, in this order:
   - Newest **Test** result bundle:
     `ls -td ~/Library/Developer/Xcode/DerivedData/<Project>-*/Logs/Test/*.xcresult | head -1`.
     Read its `runDestination.targetDeviceRecord` with
     `xcrun xcresulttool get --legacy --format json --path <xcresult>` and extract `modelName` +
     `operatingSystemVersionWithBuildNumber`. This is authoritative — it is what Xcode actually ran,
     not a menu of installed options — because it is a record of a real past test action.
   - If no `.xcresult` exists yet, `xcrun simctl list devices booted` is a weaker signal (whatever
     is currently booted in Simulator.app, which Xcode's next ⌘U will likely reuse). Present it to
     the user explicitly labeled as unconfirmed, not as the resolved value.
   - If neither exists, there is nothing to infer from this machine. Do not guess a model/OS from a
     project default or a common device — ask the user to run the test scheme once via Xcode (⌘U) so
     a ground-truth `.xcresult` exists, then repeat this step.
2. Inspect the project's Fastlane lane, CI command, test script and schemes to identify the plausible
   scheme and test target(s), and note any destination they already pin (model and/or OS). Run the
   read-only `xcrun simctl list devices available` to collect the simulator destinations actually
   installed. If this pins a different model/OS than step 1 found, surface the discrepancy to the
   user rather than silently preferring one.
3. If step 1 found an `.xcresult`-derived model + OS, that is authoritative — adopt it as the
   resolved destination directly instead of asking the user to re-type it; just show the user what
   was found (and any step 2 discrepancy) so they can object or override before it is saved. Only
   when step 1 had no `.xcresult` (booted-simulator guess or nothing at all):
   Ask the user which test target/destination to use, and require the answer to name both the
   device model and the exact OS version — an answer that only names a model (no OS) is incomplete
   and must be re-asked, since that
   is exactly the gap that causes this class of mismatch.
4. After the user answers, run the non-building
   `xcodebuild -showdestinations -scheme <scheme>` and match the selected device in its **Available
   destinations**. Use the exact per-device `OS:` value printed there. The broad runtime heading from
   `simctl` can omit a patch component (for example `26.3` while xcodebuild requires `26.3.1`);
   `xcodebuild -showdestinations` is authoritative for destination syntax.
5. Do not save the Generate/Verify commands until the user answers and that validation succeeds.
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

When needed, add the applicable flag(s) consistently to **all five** Generate/Verify templates, for
example:
```
xcodebuild test -scheme <scheme> -destination "platform=iOS Simulator,name=<device>,OS=<runtime>" -skipPackagePluginValidation -skipMacroValidation
```
Replace every `<…>` value before saving. This affects only non-interactive trust validation; it does
not replace the Generate-only recording variable or per-file `-only-testing`.

First inspect the resolved `swift-snapshot-testing` version in `Package.resolved`:

- **1.17.0 or newer:** the library officially reads `SNAPSHOT_TESTING_RECORD`; use
  `TEST_RUNNER_SNAPSHOT_TESTING_RECORD=all` so `xcodebuild` forwards it into the simulator test runner.
  This is the Generate switch — no wrapper and no source-code change are needed.
- **Older than 1.17.0:** the environment switch is unavailable. Configure Generate only when the
  project already has a verified lane/script or its own environment-driven recording hook; otherwise
  leave Generate unset and explain that upgrading to 1.17+ enables a command-only setup.

Command shape for 1.17+ (every `<…>` value must be replaced with a project value or destination
confirmed by the user):
```
--generate-all-cmd  'TEST_RUNNER_SNAPSHOT_TESTING_RECORD=all xcodebuild test -scheme <scheme> -destination "platform=iOS Simulator,name=<available-device>,OS=<installed-runtime>"'
--generate-file-cmd 'TEST_RUNNER_SNAPSHOT_TESTING_RECORD=all xcodebuild test -scheme <scheme> -destination "platform=iOS Simulator,name=<available-device>,OS=<installed-runtime>" -only-testing:<test-target>/{class_name}'
--verify-all-cmd    'xcodebuild test -scheme <scheme> -destination "platform=iOS Simulator,name=<available-device>,OS=<installed-runtime>"'
--verify-file-cmd   'xcodebuild test -scheme <scheme> -destination "platform=iOS Simulator,name=<available-device>,OS=<installed-runtime>" -only-testing:<test-target>/{class_name}'
--verify-case-cmd   'xcodebuild test -scheme <scheme> -destination "platform=iOS Simulator,name=<available-device>,OS=<installed-runtime>" -only-testing:<test-target>/{snapshot_class_name}/{snapshot_method_name}'
```

Generate and Verify intentionally share the same underlying test command; Generate differs by the
recording environment variable. If the project already has a reliable `xcodebuild` wrapper/lane,
keep that command and add only the Generate recording variable where needed rather than inventing
another test invocation. Do not run either command during automated configuration.

Before saving, audit the five commands together:

- all five use the user-confirmed scheme and exact destination from `xcodebuild -showdestinations`;
- no command sets `SNAPSHOT_ARTIFACTS` or `TEST_RUNNER_SNAPSHOT_ARTIFACTS`;
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
that they can later change a view so a snapshot differs and run its snapshot test with Xcode (`⌘U`,
a single test, or `xcodebuild test`). The failure's actual PNG may remain under
`…/CoreSimulator/…/tmp/…`; Golden Diff resolves its `@+ file://` URL from the selected `.xcresult` and
selects changed Test output automatically. If the simulator has already removed that file, rerun the
failing test to refresh the result and artifact.
