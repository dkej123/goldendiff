---
name: build-install-app
description: Build the standalone desktop app locally and install it into /Applications to test app changes fast. Use when asked to build/install/reinstall a new local version of the Golden Diff app (the Compose desktop app / the `Golden Diff.app` CLI), e.g. after editing app code or the bundled framework guides. NOT for releasing — no version bump, no git tag, no CI (that is the `deploy` skill).
---

# Build & install the standalone app locally

Fast local loop for the **`:app` Compose desktop application** (installed as `/Applications/Golden Diff.app`,
whose binary is also the config CLI at `/Applications/Golden Diff.app/Contents/MacOS/Golden Diff`).
Use this to test app-side changes — UI, CLI, or the bundled `docs/framework-guides/*` (the
`:app:copyFrameworkGuides` task copies them onto the app classpath). macOS only.

## Hard rules
- **This is a LOCAL install, not a release.** Do NOT bump `pluginVersion`, do NOT commit, do NOT tag.
  An `app-v*` / `app-beta-v*` tag is a production publish — that is the `deploy` flow, not this one.
- **`packageDmg`/`jpackage` need a FULL JDK 21+ — a JetBrains Runtime does NOT ship `jpackage`.** If
  Gradle runs on Android Studio's JBR (the usual `JAVA_HOME` here) it fails with `'jpackage' is missing`.
  Always pass `-PappJavaHome=<full JDK>`. Discover one with `/usr/libexec/java_home -V`; on this machine
  `/Users/dkwasniak/Library/Java/JavaVirtualMachines/openjdk-26.0.1/Contents/Home` works.
- **Trust the Gradle log, not the piped exit code** — confirm `BUILD SUCCESSFUL` (see docs/gotchas.md).

## Fast path — `createDistributable` (no DMG, quickest)
Produces the `.app` bundle directly, skipping DMG packaging. Prefer this for iteration.
```bash
JDK=/Users/dkwasniak/Library/Java/JavaVirtualMachines/openjdk-26.0.1/Contents/Home
./gradlew :app:createDistributable -PappJavaHome="$JDK" --console=plain
# output: app/build/compose/binaries/main/app/Golden Diff.app
```
Install it:
```bash
osascript -e 'tell application "Golden Diff" to quit' 2>/dev/null || true
pkill -f "Golden Diff.app" 2>/dev/null || true
rm -rf "/Applications/Golden Diff.app"
cp -R "app/build/compose/binaries/main/app/Golden Diff.app" /Applications/
xattr -dr com.apple.quarantine "/Applications/Golden Diff.app" 2>/dev/null || true
```

## Full path — `packageDmg` (real installer)
Use when you specifically need the `.dmg` artifact.
```bash
JDK=/Users/dkwasniak/Library/Java/JavaVirtualMachines/openjdk-26.0.1/Contents/Home
./gradlew :app:packageDmg -PappJavaHome="$JDK" --console=plain
# output: app/build/compose/binaries/main/dmg/Golden Diff-<numeric-ver>.dmg  (e.g. Golden Diff-1.5.0.dmg)
```
Install from the DMG:
```bash
DMG=$(ls -t "app/build/compose/binaries/main/dmg/"*.dmg | head -1)
MP=$(hdiutil attach "$DMG" -nobrowse -readonly | grep -o '/Volumes/.*' | head -1)
osascript -e 'tell application "Golden Diff" to quit' 2>/dev/null || true
pkill -f "Golden Diff.app" 2>/dev/null || true
rm -rf "/Applications/Golden Diff.app"
cp -R "$MP/Golden Diff.app" /Applications/
xattr -dr com.apple.quarantine "/Applications/Golden Diff.app" 2>/dev/null || true
hdiutil detach "$MP" -quiet
```

## Verify the install
```bash
"/Applications/Golden Diff.app/Contents/MacOS/Golden Diff" --version
```
If you changed a framework guide, confirm the installed CLI serves the new copy (not a stale bundle):
```bash
"/Applications/Golden Diff.app/Contents/MacOS/Golden Diff" guides
"/Applications/Golden Diff.app/Contents/MacOS/Golden Diff" guide android-roborazzi | head -15
```

## Notes
- The DMG/app filename uses the **numeric** version (`1.5.0`), while `--version` prints the full
  `pluginVersion` (`1.5.0-beta.8`); that mismatch is expected, not a bug.
- To just run the app without installing (pure runtime check, no `jpackage` needed):
  `./gradlew :app:run`.
- Quit the running app before copying, or the replace can fail with "Resource busy" / a half-copied
  bundle.
