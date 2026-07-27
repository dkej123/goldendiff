# Framework guides

Framework-specific setup GoldenDiff ships so a configuration agent can wire a project to the tool.
Read one with `guide <name>`. Consult the matching guide **before** running `configure`, and apply
both the project-side and GoldenDiff-side setup it lists.

| name | detect when |
|---|---|
| `ios-swift-snapshot-testing` | project references `pointfreeco/swift-snapshot-testing`, imports `SnapshotTesting`, or has `__Snapshots__/` reference dirs |
