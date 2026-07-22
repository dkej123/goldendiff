rootProject.name = "golden-diff"

// :core holds the tool-agnostic logic (golden matching, git access, pixel diff) with no dependency on
// the IntelliJ Platform, so the same code can back both the IDE plugins and a standalone desktop app.
include(":core", ":core-ui", ":public-plugin", ":internal-plugin", ":app")
