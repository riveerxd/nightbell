# Nightbell brand assets

One mark, generated into every place that needs it.

## The mark

The heartbeat trace knocked out of a bell. The trace is the same six points the
Pulse mark was made of, scaled to 0.42 and centred inside the bell rather than
redrawn, so the old identity is literally the same line rather than a nod to it.

| File | What it is |
| --- | --- |
| `nightbell-mark-icon.svg` | 512 × 512, `rx=112` ink plate. The master. Launcher, favicon, store listing. |
| `nightbell-mark.svg` | the same drawing with the plate removed, transparent. |
| `logo-dark.svg` / `logo-light.svg` | mark + wordmark, 725 × 190, the theme-aware pair the README uses. |

The cutout is a real `<mask>`, not a dark shape painted over the plate. That is
what lets one mark sit on the dark page and on a light printed sheet: the hole
shows whatever is behind it. It is also why the Android copies need a different
construction, which `android_assets.py` explains at length.

## Generated, so edit the generator

```bash
python3 docs/brand/lockups.py         # logo-dark.svg + logo-light.svg
python3 docs/brand/android_assets.py  # the five res/drawable vectors (needs shapely)
bash    docs/brand/readme_shots.sh <raw_capture_dir>   # docs/screens/*.png
```

`lockups.py` measures the canvas width off the rendered ink instead of taking a
number on trust. That is not fussiness: the wordmark went from five letters to
nine in 3.0.0, and a hand-set canvas that no longer fits its contents clips
without saying anything.

Four more copies of the geometry exist outside this directory, and all four say so
in their own header comments:

- `app/src/main/kotlin/me/river/nightbell/ui/components/BrandMark.kt`, the Compose mark
- `website/src/components/Mark.astro`, inlined so it is in the HTML on first paint
- `website/scripts/sync-assets.mjs`, which reads `nightbell-mark-icon.svg` off disk
- `promo-video/src/components/BrandMark.tsx`, which animates the cut being carved

## `verify-mark.png`

Every surface that ships the mark, rendered side by side: the six Android
drawables, the brand master, the favicon, the PWA icon, the README lockup, the
in-app header off a real device capture, and a frame of the promo.

It exists because "the icon was updated" is a claim and twelve pictures in one
file is a check, and it earned its keep immediately: 3.0.0 and 3.0.1 shipped with
the widget header and the status-bar glyph as solid bells while every other
surface carried the cutout, and the sheet is where that disagreement is obvious
at a glance. Regenerate it whenever the mark changes.

## `archive/pulse-directions/`

Thirty logo directions from when the app was called Pulse, each a square icon
plate and a landscape wordmark lockup, with `build.py` and the `logos.html`
presentation sheet that produced them. Direction 30 is the mark that shipped from
2.0.0 to 2.5.0.

They are kept unchanged and not regenerated. They are the record of a decision
about a different name, and restamping them with "Nightbell" would turn an honest
piece of history into thirty explorations that never happened.
