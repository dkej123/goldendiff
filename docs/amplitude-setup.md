# Amplitude product analytics setup

Amplitude is the product-analytics system for Golden Diff. Sentry remains responsible for diagnostic
exceptions and performance spans. Do not duplicate product events in Sentry.

Both the standalone app and public IDE plugin send to one Amplitude project. Every event includes
`surface=desktop` or `surface=plugin`, so reports can compare the products or filter either one out.
The internal Figma plugin does not send telemetry.

## Create the project

1. Create an Amplitude organization and select the **EU data region**. Data region is an account
   decision and cannot be changed on an existing project by changing an SDK URL.
2. Create one project named **Golden Diff Product Analytics**.
3. In the project settings, copy the project **API Key**. This is the public ingestion identifier,
   not an API secret, management key or service-account credential.
4. Keep session replay disabled. It is a web SDK feature and is not used for the Compose/Swing UI.
5. Do not enable automatic collection or add an Amplitude browser SDK. Golden Diff uses the
   official Amplitude JVM SDK and sends only the privacy-reviewed event catalog over HTTP API V2.

The transport is pinned to the EU endpoint:

```text
https://api.eu.amplitude.com/2/httpapi
```

The default project key is stored as `amplitudeApiKey` in `gradle.properties` because an ingestion
key is public by design and will necessarily be present in the distributed app and plugin. It can be
overridden without editing the repository:

```bash
./gradlew :app:run -PamplitudeApiKey='PROJECT_API_KEY'
./gradlew :public-plugin:buildPlugin -PamplitudeApiKey='PROJECT_API_KEY'
```

An empty value disables Amplitude:

```bash
./gradlew :app:run -PamplitudeApiKey=
```

Use the same key for both public surfaces. Do not put an Amplitude secret key or management token in
Gradle properties, GitHub Actions, the app, or the plugin ZIP.

## Verify ingestion

Use a development build and real opt-in instead of sending a synthetic event outside the application:

1. Open Amplitude's project setup/data view and its live event stream (the label may be **Debugger**
   or **Event Stream**, depending on the current UI).
2. Run the standalone app:

   ```bash
   ./gradlew :app:run
   ```

3. In the first-run privacy dialog, enable **Anonymous usage analytics**. If the dialog was already
   answered, enable it in **Settings → Privacy**.
4. Wait a few seconds. Confirm `product.session_started` in the live stream. On the first opted-in
   run for that local installation, `product.installation_first_seen` should appear immediately
   before it.
5. Load and display a comparison. Confirm `product.comparison_viewed`.
6. Inspect the raw properties. Confirm:
   - `surface` is `desktop`;
   - `user_id` remains the same across an app restart;
   - `session_id` changes across an app restart;
   - `app_version` and `release_channel` are present;
   - no path, project name, filename, class name, source code or image content is present.
7. Repeat with the public plugin: open its tool window, opt in under
   **Settings → Tools → Golden Diff → Privacy**, and confirm the same events with
   `surface=plugin`. Also confirm `ide_product` and the coarse `ide_build_major`.
8. Turn analytics off and exercise the product again. No subsequent product event should arrive.

Amplitude ingestion is asynchronous. A successful local build is not proof of delivery; the event
and its expected properties must be visible in the Amplitude project before a stable release.

## Identity and counting rules

Amplitude `user_id` and `device_id` both use Golden Diff's random installation ID. The ID is generated
only after analytics opt-in and normally survives application/plugin updates:

- desktop: persisted in Golden Diff's user-level settings outside the `.app` bundle;
- plugin: persisted in IntelliJ Platform application-level properties.

It is not shared between the standalone app and plugin installations. The `surface` property is
therefore always required when comparing their populations. Reinstalling after deleting user
settings, resetting an IDE profile, or manually clearing the settings creates a new anonymous
installation and will be counted as a new user.

`product.installation_first_seen` means first analytics-visible use after opt-in. It does not count a
download, an installation that was never opened, or use before consent.

## Recommended reports

Create a small dashboard set from the existing event catalog. Apply `surface` as a segment on every
audience, retention and adoption chart, and add `app_version` or `release_channel` where relevant.

1. **Audience and versions**
   - unique users of `product.session_started`;
   - new users from `product.installation_first_seen`;
   - DAU, WAU and MAU;
   - group by `surface`, then `app_version` and `release_channel`.
2. **Activation**
   - funnel: `product.installation_first_seen` → `product.activation_completed`;
   - conversion time and `time_to_value_bucket`;
   - segment by `surface`, `scope` and `source`.
3. **Product retention (D1/D7/D30)**
   - start event: `product.activation_completed`;
   - return event: `product.comparison_viewed`;
   - use exact-day/N-day retention for D1, D7 and D30;
   - compare `desktop` and `plugin` as separate segments.
4. **Usage retention**
   - start and return event: `product.comparison_viewed`;
   - this answers whether people who reached the core value repeat it.
5. **Feature adoption**
   - `product.compare_mode_selected` by `mode`;
   - `product.feature_used` by `feature`;
   - Project changes from `product.browse_scope_selected`;
   - Test output from `product.comparison_source_selected`.
6. **Workflow quality**
   - `product.scan_completed` by `result` and `blocker`;
   - comparisons by `result`, `scope`, `source` and `surface`;
   - `product.operation_failed` by `operation` and `error_category`.

These Amplitude retention reports are cohort retention: a denominator is formed from users who
performed the start event, and return percentages are calculated relative to that cohort. Do not
substitute rolling 7/30-day active-user counts or `installation_age_bucket`; those are audience
activity measures, not D1/D7/D30 retention.

## Release checklist

Before beta:

- verify standalone and plugin events in the EU project;
- verify stable `user_id` and changing `session_id`;
- verify consent opt-out stops delivery;
- inspect payloads from all catalog events for privacy;
- save the audience, activation, retention, adoption and workflow reports;
- confirm Amplitude contains product events while Sentry contains exceptions/performance spans;
- confirm the internal plugin ZIP has no telemetry integration.

Repeat the payload check on beta artifacts before publishing stable.
