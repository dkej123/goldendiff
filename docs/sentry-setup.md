# Sentry setup and dashboards

Repository code is ready for two Sentry Cloud projects in the European region:

- `golden-diff-app`
- `golden-diff-plugin`

Keep **Send Default PII**, IP storage/GeoIP, server name, attachments, profiling and replay disabled.
Use the Free plan without a payment method or pay-as-you-go. Inject the project DSNs at build time:

```bash
./gradlew :app:packageDmg -PsentryAppDsn='https://…'
./gradlew :public-plugin:buildPlugin -PsentryPluginDsn='https://…'
```

CI should provide the same values as secret-backed Gradle properties. An empty property deliberately
builds an offline `NoOp` configuration.

Add these GitHub Actions repository secrets under **Settings → Secrets and variables → Actions**:

- `SENTRY_APP_DSN`
- `SENTRY_PLUGIN_DSN`
- `SENTRY_AUTH_TOKEN` — a CI-only organization token with `org:ci`; never embed it in an app or
  plugin package.

The app packaging and public plugin publish/release workflows already map them to the matching Gradle
properties. Tagged app and public-plugin workflows use the auth token to create/finalize matching
Sentry releases and associate commits. The internal-plugin workflow receives neither DSN nor token.

## Release checklist

Before creating any `v*`, `beta-v*`, `app-v*`, or `app-beta-v*` tag:

1. Run `gh secret list --repo dkej123/goldendiff` and require all of `SENTRY_APP_DSN`,
   `SENTRY_PLUGIN_DSN`, and `SENTRY_AUTH_TOKEN`. This command shows names and update timestamps, not
   secret values.
2. Keep `SENTRY_AUTH_TOKEN` CI-only and use the minimum suitable scope (`org:ci` is preferred). Never
   put it in `gradle.properties`, a workflow file, a shell command argument, logs, an app/plugin
   resource, or release notes.
3. For a release build, confirm the appropriate DSN is injected while the other product's DSN is not
   added unnecessarily. The Figma plugin receives no Sentry credentials and has no separate Sentry
   release.
4. After the tag workflow completes, require the **Create Sentry release** step to be `success` rather
   than `skipped`. Confirm the release name, environment, URL, deploy, and associated commits:
   - public plugin: `golden-diff-plugin@<version>`;
   - desktop app: `golden-diff-app@<version>`;
   - stable tags use `stable`; beta tags use `beta`.

The workflows currently skip Sentry release creation when `SENTRY_AUTH_TOKEN` is empty. That skip does
not fail Marketplace, GitHub Release, DMG, or Homebrew publication, so a green overall workflow is not
enough evidence that Sentry was updated.

### Backfilling a missed release

If an artifact was published while the auth token was absent:

1. Add or repair `SENTRY_AUTH_TOKEN` in GitHub Actions secrets and verify only its name is listed.
2. Check whether the exact Sentry release already exists before writing anything, so the operation is
   idempotent.
3. Create/finalize only the missing `golden-diff-plugin@<version>` or
   `golden-diff-app@<version>` release through the Sentry UI, CLI, or the organization Releases API.
   Use the original publication time and release URL, associate the tagged release HEAD (and the
   previous release commit when required), then create a deploy for the correct `stable` or `beta`
   environment.
4. Read the release back and verify the project, `dateReleased`, environment, deploy count, commit
   count, and last commit.

Do not rerun the full Marketplace or app packaging workflow solely to repair Sentry. It can attempt a
duplicate Marketplace upload, duplicate release assets, or another Homebrew update. Never paste an
auth token into documentation, commits, logs, or a command captured in shell history.

Release Health sessions are emitted only while crash reporting consent is enabled. Their environment
is `stable`, `beta`, or `dev`; opting out ends the current Sentry session immediately.

## Dashboard set

Create at most these eight dashboards from `product.*` transactions and performance spans:

1. Audience and versions: `product.installation_first_seen`, seven/30-day active installation IDs,
   and active installation IDs grouped by `app_version`, `release_channel`, and `surface`.
2. `product.activation_completed`, grouped by `time_to_value_bucket`.
3. Comparisons grouped by `surface`, `scope` and `source`.
4. `product.compare_mode_selected`, grouped by `mode`.
5. Adoption of Quick Open, Project changes, Test output and detached comparison.
6. `product.scan_completed`, grouped by `result` and `blocker`.
7. p50/p95 of `golden.scan`, `git.head_read`, `image.decode`, `pixel_diff.compute` and
   `comparison.load`.
8. Crash-free sessions, errors by release, and `product.operation_failed` by operation/category.

Seven- and 30-day unique active installations are activity windows, not D1/D7/D30 cohort retention,
and should be labelled accordingly. `product.installation_first_seen` means the first launch visible
after analytics opt-in, not a download or an installation where consent was declined. True cohort
retention can be calculated after exporting first-seen and subsequent `product.session_started`
timestamps grouped by the stable anonymous `user.id`; Sentry Free should not label its rolling
seven/30-day activity widgets as cohort retention.

For an in-product return signal, `product.session_started.installation_age_bucket` groups active
anonymous installations into `first_day`, `day_1`, `days_2_6`, `days_7_29`, `days_30_89`, and
`days_90_plus`. The Audience dashboard labels this as return activity/retention proxy, not a cohort
percentage.

Before stable release, use beta builds to exercise every catalog event and one controlled exception,
then inspect the raw payloads for paths, names, source code and image data. Dashboard creation,
organization region selection and payload inspection are manual Sentry account actions and are not
performed by the Gradle build.
