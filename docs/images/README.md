# Screenshots to capture

The guide pages reference the images below with clearly-marked placeholders. Until the real files
are added here, each page carries an `<!-- TODO(screenshot) -->` comment describing the shot. To find
every placeholder still outstanding:

```bash
grep -rn 'TODO(screenshot)' docs
```

## Capture checklist

| File | Used in | What to capture |
|---|---|---|
| `tool-window.png` | [home](../index.md) | The whole tool window: golden list on the left, a side-by-side comparison on the right. The hero image. |
| `install-marketplace.png` | [installation](../installation.md) | Settings → Plugins → Marketplace with **Golden Diff** found and the **Install** button visible. |
| `getting-started-first-run.png` | [getting-started](../getting-started.md) | The tool window on first run — empty, showing the **Choose screenshots directory** button. |
| `getting-started-comparison.png` | [getting-started](../getting-started.md) | A selected golden showing a side-by-side HEAD vs Working copy comparison. |
| `compare-side-by-side.png` | [comparing](../comparing.md) | Side-by-side mode: HEAD on the left, Working copy on the right, labels visible. |
| `compare-swipe.png` | [comparing](../comparing.md) | Swipe mode with the divider partway across, both labels visible. |
| `compare-onion-skin.png` | [comparing](../comparing.md) | Onion-skin mode with the opacity slider around 50%. |
| `compare-diff.png` | [comparing](../comparing.md) | Diff mode showing the magenta heatmap and the **“% pixels changed”** readout. |
| `settings.png` | [settings-reference](../settings-reference.md) | The full **Settings → Tools → Golden Diff** page. |
| `settings-live-preview.png` | [settings-reference](../settings-reference.md) | The **Preview (live)** panel showing a match count and a list of matched golden paths. |

## Tips for good screenshots

- Use a **light IDE theme** for readability in docs, and a consistent window size across shots.
- Use a golden with a **clearly visible change** so the compare modes (especially swipe, onion skin, and
  diff) are easy to read.
- A short **GIF** of swipe or onion skin is a nice addition for the Marketplace listing; if you add one,
  reference it from [comparing.md](../comparing.md).
