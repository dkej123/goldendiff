# Privacy

Golden Diff does not send telemetry unless you explicitly opt in. Anonymous product analytics and
diagnostic error reporting are separate choices, both disabled by default. You can change either
choice at any time:

- Desktop app: **Settings → Privacy**
- IDE plugin: **Settings → Tools → Golden Diff → Privacy**

Turning a choice off takes effect immediately and clears queued telemetry from the running client.
Declining the first-run prompt keeps both choices off.

## What product analytics contains

If enabled, Golden Diff sends coarse events about the features used and how long operations take.
Examples include the selected comparison mode, whether a scan found zero or several items, and a
bucketed comparison-load duration. Events include a random installation ID, a random ID valid only
for the current session, the Golden Diff version, release channel, operating-system family and major
version, and JVM major version. The IDE plugin also sends only the IDE family and build baseline
(for example, `android_studio` and `241`).

Product events never contain project names, file or directory names, paths, class or method names,
source code, image dimensions, image data, clipboard contents, or identifiers from optional
comparison-source extensions.

## What diagnostic reporting contains

If enabled, Golden Diff sends sanitized exceptions caught at Golden Diff boundaries. Absolute paths,
the home directory and project root are removed from exception messages before sending. The IDE
plugin does not install a global exception handler and does not report failures from the IDE or other
plugins.

Golden Diff does not send attachments, screenshots, replay, profiling data, IP-derived location,
server names, or user-entered configuration values.

## Processing and limits

The two choices use different processors:

- **Amplitude Analytics in the European data region** receives anonymous product events. The
  standalone app and public IDE plugin use one project and remain independently segmentable through
  the `surface` property (`desktop` or `plugin`).
- **Sentry Cloud in the European region** receives diagnostic exceptions and privacy-safe
  performance spans. Separate Sentry projects are used for the standalone app and public IDE plugin.

The anonymous installation ID is created only after analytics consent. It is stored outside the
application bundle (or in the IDE's application-level settings), so it normally survives Golden Diff
updates. It is not an account identifier and cannot be connected to a person's identity by Golden
Diff. A separate random session ID changes on every application or plugin session.

Amplitude receives data only through its EU ingestion endpoint. Its project API key is an ingestion
identifier that is public by design; Golden Diff never contains an Amplitude management key, secret
key or account credential. Sentry is configured without default PII collection or IP/GeoIP
retention.

Golden Diff limits diagnostic reports to 20 per session (with five-minute duplicate suppression) and
product events/performance spans to 200 per installation per UTC day. Turning analytics or
diagnostics off clears the corresponding in-memory queue. Builds without the relevant configuration
stay offline for that processor. The internal Figma plugin does not include or initialize telemetry.

No session replay is recorded. Amplitude Session Replay for web applications is not used by the
Compose desktop app or Swing IDE plugin.

The project uses provider free quotas without pay-as-you-go. If a quota is exhausted, the provider
drops new data rather than creating a charge.

Questions or privacy requests can be opened through the
[Golden Diff issue tracker](https://github.com/dkej123/goldendiff/issues).
