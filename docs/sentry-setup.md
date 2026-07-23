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
