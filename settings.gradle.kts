pluginManagement {
    repositories {
        // google() is only needed for the Android Gradle Plugin used by :sample-android.
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "golden-diff"

// :core holds the tool-agnostic logic (golden matching, git access, pixel diff) with no dependency on
// the IntelliJ Platform, so the same code can back both the IDE plugins and a standalone desktop app.
// :sample-android is a local manual-QA fixture: a real Android/Compose/Roborazzi project used to
// exercise GoldenDiff's new/edited/failed golden comparisons end-to-end.
include(":core", ":core-ui", ":telemetry", ":public-plugin", ":internal-plugin", ":app", ":sample-android")
