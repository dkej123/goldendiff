---
name: deploy
description: Release/deploy runbook for the Golden Diff plugins. Use when asked to cut a release, publish, deploy, ship a new version, or bump-and-tag either the public plugin (Golden Diff → JetBrains Marketplace) or the internal Figma plugin (Golden Diff — Figma → custom repo). Covers version bump, mandatory CHANGELOG, build/test verification, commit, tag, push, and CI monitoring.
---

# Deploy — Golden Diff

Two plugins release independently. Decide which one from the changed files:
`public-plugin/**` → **public plugin**; `internal-plugin/**` → **internal Figma plugin**.
Both versions live in root `gradle.properties` (`pluginVersion` = public, `figmaPluginVersion` = Figma).

## Hard rules (do not skip)
- **No AI mention.** Commit messages, tags, CHANGELOG and release notes must not name any AI tool
  (no Codex, no Claude, no Co-Authored-By trailer). Project instruction overrides the default trailer.
- **CHANGELOG entry is mandatory for the public plugin.** `.github/workflows/release.yml` extracts
  notes by matching `## [<version>]` in `CHANGELOG.md`; a missing entry **fails the release job**.
- **Verify the Gradle log for `BUILD SUCCESSFUL`** — never trust a piped exit code (see docs/gotchas.md).
- **Pushing a `v*` tag is a production publish to JetBrains Marketplace** (irreversible). Only do it
  when the user has explicitly authorized this release. It triggers `Release` + `Publish Plugin`.

## A. Public plugin → Marketplace (tag-driven)
1. **Bump** `pluginVersion` in `gradle.properties`. Patch for fixes/small UI, minor for a new
   user-facing feature (match how prior versions were bumped).
2. **CHANGELOG.md** — insert a new `## [<ver>] - <YYYY-MM-DD>` section above the latest, with
   `### Added/Changed/Fixed` bullets. Feature-focused wording, no AI mention.
3. **Verify build + tests:**
   ```bash
   ./gradlew :public-plugin:test :public-plugin:buildPlugin --console=plain
   ```
   Confirm `BUILD SUCCESSFUL` and that `public-plugin/build/distributions/golden-diff-<ver>.zip` exists.
4. **Commit to `main`** (style: `<summary> (<ver>)`, e.g. `Add copyable golden-name header … (1.4.1)`).
   Stage: the changed sources, any new tests, `gradle.properties`, `CHANGELOG.md`.
5. **Push + tag** (only with explicit go-ahead — this is the production publish):
   ```bash
   git push origin main
   git tag v<ver> && git push origin v<ver>
   ```
   If `git push origin main` is rejected (remote ahead — the internal-publish bot commits to `main`),
   `git fetch origin && git rebase origin/main`, re-verify, then push. Tag AFTER the rebased commit
   is on the remote so the tag points at the final commit.
6. **Monitor:** `gh run list --limit 5` — expect `Release` (GitHub release + zip) and
   `Publish Plugin` (Marketplace upload) for the tag. Confirm both succeed:
   `gh run watch <id>` or re-list. Requires repo secret `JETBRAINS_MARKETPLACE_TOKEN`.

Published listing: https://plugins.jetbrains.com/plugin/32662-golden-diff

## B. Internal Figma plugin → custom repo (push-driven, automated)
No tag. Just:
1. Bump `figmaPluginVersion` in `gradle.properties`.
2. (Optional) Note the change; there is no separate CHANGELOG gate for it.
3. Verify: `./gradlew :internal-plugin:buildPlugin --console=plain` → `BUILD SUCCESSFUL`,
   `internal-plugin/build/distributions/golden-diff-figma-<ver>.zip`.
4. Commit + `git push origin main`. The `publish-internal.yml` workflow rebuilds and commits refreshed
   `distribution/{ZIP,updatePlugins.xml}` back to `main` (bot commit uses `GITHUB_TOKEN`, no loop).
   Local fallback/preview only: `./distribution/publish.sh`.
5. Monitor: `gh run list` → `Publish internal plugin`. Team auto-updates from the custom repo.

## Reference docs
- `docs/marketplace.md` — Marketplace listing, release checklist, token secret.
- `docs/build-and-run.md` — commands, internal-plugin distribution mechanics.
- `docs/gotchas.md` — build traps (the `BUILD SUCCESSFUL` log check, open-ended `untilBuild`).
