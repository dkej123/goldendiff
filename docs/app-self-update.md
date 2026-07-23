# Standalone app — one-click update install (Homebrew + DMG)

## Context
The standalone app already checks for updates (`UpdateChecker`) and shows a bottom **StatusBar** link
and a one-time **UpdateBanner** whose action currently just opens the GitHub release page in a
browser. The goal is for the button to actually **fetch and apply** the update. Since most users
install via Homebrew, two install paths are supported, chosen automatically by how the app was
installed:

- **Homebrew** (detected) → run `brew upgrade --cask <cask>` **in-app, in the background**, showing a
  spinner + status; on success prompt to restart.
- **Otherwise** → download the release `.dmg` to `~/Downloads` and **open it automatically** (mounts
  the installer), so the user drags the app to `/Applications`.

macOS reality (unchanged by this work): the app is **ad-hoc signed, not notarized**, so an updated
bundle is re-quarantined — the existing `xattr -dr com.apple.quarantine` caveat still applies. A short
hint is surfaced after updating; true silent self-update would require notarization (out of scope).

## Key facts (verified)
- DMG URL is deterministic (same as the casks build):
  `https://github.com/dkej123/goldendiff/releases/download/<tag>/Golden-Diff-<version>.dmg`,
  `tag` = `app-beta-v<version>` (beta) / `app-v<version>` (stable). See `Casks/golden-diff@beta.rb`.
- Casks: `golden-diff@beta` (beta) / `golden-diff` (stable), tap `dkej123/goldendiff`.
  Upgrade = `brew upgrade --cask <cask>` (leave brew auto-update ON so the bumped cask in the tap is
  picked up).
- Fast, reliable Homebrew detection: check `<prefix>/Caskroom/<cask>` exists, `<prefix>` ∈
  `/opt/homebrew`, `/usr/local`; brew binary at `<prefix>/bin/brew`. Avoids slow `brew list`.
- Channel/version come from `AppTelemetry.releaseChannel` and the found `update.version`.

## Changes

### 1. New: `app/.../UpdateInstaller.kt`
Pure JVM helpers (mirrors `UpdateChecker` style), all blocking calls meant for `Dispatchers.IO`:
- `data class HomebrewCask(val brew: File, val cask: String)`
- `homebrewCask(channel): HomebrewCask?` — cask name by channel (`golden-diff@beta` / `golden-diff`);
  return the first prefix where both `<prefix>/bin/brew` and `<prefix>/Caskroom/<cask>` exist.
- `upgradeViaHomebrew(cask, onLine: (String) -> Unit): Int` —
  `ProcessBuilder(cask.brew.path, "upgrade", "--cask", cask.cask)`, `redirectErrorStream(true)`, put
  `<prefix>/bin` on `PATH`, stream merged output lines to `onLine`, return exit code.
- `dmgUrl(version, channel): String` — construct per the fact above.
- `downloadDmg(url, version): File` — `HttpClient` (follow redirects — GitHub redirects to a CDN) →
  `~/Downloads/Golden-Diff-<version>.dmg` (fallback to a temp dir if Downloads missing); return file.
- `openDmg(file)` — `java.awt.Desktop.getDesktop().open(file)`.

### 2. `app/.../AppState.kt`
- Cache detection once when an update is found: `val updateHomebrew: UpdateInstaller.HomebrewCask?`
  (lazy), and expose `val updateViaHomebrew: Boolean`.
- Add UI state: `var updateBusy by mutableStateOf(false)`,
  `var updateStatus by mutableStateOf<String?>(null)` (short human status / latest brew line / error).
- `fun installUpdate()` — guard on `updateBusy`; `scope.launch`:
  - Homebrew path: `featureUsed("update_homebrew")`; run `upgradeViaHomebrew` on IO, push the last
    line to `updateStatus`; on exit 0 → `updateStatus = "Updated — restart Golden Diff to finish."`
    (+ quarantine hint), else → error + keep `openUpdatePage()` fallback available.
  - Download path: `featureUsed("update_download")`; `updateStatus = "Downloading…"`, `downloadDmg`
    on IO, then `openDmg`; on failure set error and call `openUpdatePage()`.
- Keep existing `openUpdatePage()` (release page) as a secondary "details" link and
  `dismissUpdateBanner()`.

### 3. `app/.../GoldenDiffWindow.kt`
- **UpdateBanner**: primary button label
  `if (updateViaHomebrew) "Update with Homebrew" else "Download update"`; disabled while
  `updateBusy`; when busy show a small spinner + `updateStatus`; keep the ✕ dismiss.
- **StatusBar**: the "Update available: X" link calls `installUpdate()` (so the action still exists
  after the banner is dismissed); show `updateStatus` when busy.
- Reuse existing tokens/`Text`/`clickable`/`hoverWash` patterns already in the file.

### 4. `telemetry/.../EventCatalog.kt`
Add `"update_download"` and `"update_homebrew"` to the `product.feature_used` → `feature` allowlist
(next to the existing `update_open`).

## Verification
- Lower `pluginVersion` (as in the earlier demo), `rm -f ~/.config/golden-diff/update.properties`,
  `./gradlew :app:run` → banner + status appear.
- **Homebrew machine** (beta cask under `…/Caskroom/golden-diff@beta`): button reads "Update with
  Homebrew" → click → spinner, brew output streamed, ends "restart to finish". Confirm
  `<prefix>/bin/brew upgrade --cask golden-diff@beta` is what runs.
- **Non-brew machine**: button reads "Download update" → `.dmg` lands in `~/Downloads` and the
  installer window opens; on a forced network error it falls back to the release page.
- `./gradlew :telemetry:test :app:test` stay green (feature keys valid; no logic regressions).
- Restore `pluginVersion` to `1.5.0-beta.4` afterward.

## Out of scope
Notarization / true silent in-place self-update; Windows/Linux updaters (app is macOS-only today).
