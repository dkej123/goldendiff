# sample-android

A local manual-QA fixture: a real Android + Jetpack Compose + Roborazzi project used to exercise
GoldenDiff's comparison modes against a real screenshot-test setup. Not part of the plugin or
desktop-app build graph, and not published anywhere.

Nine screens live under `src/main/kotlin/.../ui/screens`, each captured by one hand-written
Roborazzi test in `ScreenshotTests.kt`. Six are just a varied, good-looking gallery
(`LoginScreen`, `ProfileScreen`, `ProductListScreen`, `ProductDetailScreen`, `SettingsScreen`,
`ChatScreen`). Three are set up to permanently demonstrate GoldenDiff's three comparison outcomes:

| Screen | Case | What's going on |
| --- | --- | --- |
| `GreetingCardScreen` | **Edited** | Committed golden is the "v1" render. The working tree currently has an uncommitted source tweak plus a re-recorded golden that differs from HEAD. |
| `WelcomeBannerScreen` | **New** | The composable and test are committed, but the golden PNG itself is untracked — it only exists locally after running `recordRoborazziDebug`. |
| `StatusBadgeScreen` | **Failed** | The committed golden and the committed composable permanently disagree by design, so `verifyRoborazziDebug` always fails on this one test and writes `_actual`/`_compare` output. |

## Commands
```bash
./gradlew :sample-android:recordRoborazziDebug   # (re)generate all goldens
./gradlew :sample-android:verifyRoborazziDebug   # expect exactly one failure: statusBadgeScreen
```

## GoldenDiff tool-window configuration
```
--golden-dir     sample-android/src/test/screenshots
--generated-dir  sample-android/build/outputs/roborazzi
--match-mode     annotated   # test methods are @Test-annotated, matching the default regex
```
