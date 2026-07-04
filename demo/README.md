# Demo assets (not part of the plugin build)

This folder is **only** for producing screenshots/GIFs for the Marketplace listing. It is outside the
Gradle source sets (`src/main`, `src/test`), so nothing here is compiled into the plugin.

## `ProfileScreen.kt`
A self-contained Material 3 Compose screen with no references to this plugin — a neutral, good-looking
subject for the golden images. Package `com.example.goldendiffdemo`.

## Turn it into golden PNGs
It needs an Android/Compose environment to render (this repo has no Compose deps). Two easy paths:

1. **Compose Preview screenshot** — paste the file into any Android module that has
   Compose + Material 3, open the `ProfileScreenPreview` preview, and export/screenshot it.
2. **Snapshot test (Paparazzi / Roborazzi)** — render `ProfileScreen()` from a test and let the tool
   write the golden PNG.

## Stage a clean "before / after" diff
The compare views need two versions of the same golden. Render once, commit it, then make **one
obvious change** and render again as the working copy:

- change `Accent` to `Color(0xFF7C4DFF)` (button + icon tint shifts blue → purple), or
- change the followers count `"8,472"` to `"9,140"`, or
- swap the avatar initials / a menu label.

Aim for roughly **5–30 % changed pixels** so the pixel-diff heatmap looks meaningful (not a full
wash, not "No changes vs HEAD").

## Then, in the plugin
1. Put the committed golden in a directory (e.g. `snapshots/ProfileScreen.png`) and the changed image
   as the working copy of the same file — or drop a `ProfileScreen_actual.png` in a generated-output
   directory for the Test output source.
2. Point Golden Diff at the directory, select the golden, and capture Side-by-side, Swipe, Onion skin,
   and Diff for the listing.
