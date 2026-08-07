#!/usr/bin/env python3
"""Generate the app's Android vector drawables from the Pulse mark geometry.

    python3 docs/brand/android_assets.py

The mark is the heartbeat trace on its own. The ring that direction 30 drew around it was
dropped in 2.4.3, so every copy is now the one path. It still exists in five places — the
launcher icon, an adaptive foreground, a themed silhouette, the widget header and the
notification small icon — and each needs a different stroke weight on a different canvas
in a different colour. They are derived from the numbers in `pulse-30-ringpulse-icon.svg`
rather than scaled by hand: the first hand-scaled set shipped a Compose mark and a legacy
icon whose trace vertices were plain wrong, and anything with this much repeated arithmetic
should be computed.

Run it after changing any geometry below, then rebuild.
"""

import os

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.normpath(os.path.join(HERE, "..", "..", "app", "src", "main", "res"))

# The mark is the brand blue now that the trace is the whole mark. It used to be red — the
# one element that meant failure — but with the ring gone the trace *is* the identity, and
# a logo drawn as a single red line reads as permanently broken. Red still means failure
# everywhere the data lives (charts, the history strip, the status orbs); it is just no
# longer the logo. White is for the two places the mark is a flat silhouette: the status-bar
# icon (masked and tinted by the system) and the themed-icon monochrome layer.
BLUE, WHITE = "#2F6BFF", "#FFFFFFFF"

# The source drawing, in its own 512-unit space.
MID = 256.0
TRACE_STROKE = 26.0

# The trace: a flat line, a short beat up, the tall spike down, back to the line.
TRACE = [(107, 256), (168, 256), (200, 172), (252, 344), (284, 256), (405, 256)]


def trace_geometry(canvas, fill):
    """Fit the trace's stroked bounding box to `fill` of the canvas and centre it. Width
    dominates, so the mark fills side to side and sits vertically centred."""
    xs = [x for x, _ in TRACE]
    ys = [y for _, y in TRACE]
    # A half stroke-width of cap/join spills past every extreme vertex.
    bw = (max(xs) - min(xs)) + TRACE_STROKE
    bh = (max(ys) - min(ys)) + TRACE_STROKE
    scale = (canvas * fill) / max(bw, bh)
    cx, cy = (min(xs) + max(xs)) / 2.0, (min(ys) + max(ys)) / 2.0
    c = canvas / 2.0
    return dict(
        trace_stroke=TRACE_STROKE * scale,
        trace=" ".join(
            ("M" if i == 0 else "L") + f"{c + (x - cx) * scale:.2f},{c + (y - cy) * scale:.2f}"
            for i, (x, y) in enumerate(TRACE)
        ),
    )


def vector(canvas, body, tint=False, header=""):
    tint_attr = '\n    android:tint="#FFFFFFFF"' if tint else ""
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"{header}"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        f'    android:width="{canvas:g}dp"\n'
        f'    android:height="{canvas:g}dp"\n'
        f'    android:viewportWidth="{canvas:g}"\n'
        f'    android:viewportHeight="{canvas:g}"{tint_attr}>\n'
        f"{body}"
        "</vector>\n"
    )


def stroke(path, colour, width, cap=None):
    cap_attr = f'\n        android:strokeLineCap="{cap}"' if cap else ""
    return (
        "    <path\n"
        f'        android:pathData="{path}"\n'
        '        android:fillColor="#00000000"\n'
        f'        android:strokeColor="{colour}"\n'
        f'        android:strokeWidth="{width:.2f}"{cap_attr}\n'
        '        android:strokeLineJoin="round" />\n'
    )


ASSETS = {
    # The launcher icon. Legacy rather than adaptive, because AdaptiveIconDrawable.draw()
    # fills its layer bitmap with Color.BLACK before compositing and so can never be
    # transparent. No mask to survive, so the trace fills the canvas.
    "drawable/ic_launcher_mark.xml": dict(
        canvas=108, fill=0.92, colour=BLUE,
        note="Launcher icon. Legacy (not adaptive) so the background is genuinely\n"
             "    transparent — see docs/brand/android_assets.py for why adaptive cannot be.",
    ),
    # Kept for whenever a plate is wanted again: inset to the 33-unit radius that every
    # adaptive mask is guaranteed to keep, at the cost of looking smaller.
    "drawable/ic_launcher_foreground.xml": dict(
        canvas=108, fill=0.60, colour=BLUE, trace_scale=1.15,
        note="Adaptive-icon foreground, inset inside the guaranteed mask circle.\n"
             "    Unused while the launcher icon is the transparent legacy one.",
    ),
    "drawable/ic_launcher_monochrome.xml": dict(
        canvas=108, fill=0.60, colour=WHITE, trace_scale=1.15,
        note="Themed-icon silhouette. Only the adaptive format carries this, so it is\n"
             "    unused while the launcher icon is legacy.",
    ),
    "drawable/ic_widget_mark.xml": dict(
        canvas=24, fill=0.86, colour=BLUE, trace_scale=1.1,
        note="Widget header mark, drawn at 18dp. The brand blue rather than tinted to\n"
             "    the palette: at that size the blue heartbeat is what identifies it.",
    ),
    "drawable/ic_stat_brand.xml": dict(
        canvas=24, fill=0.86, colour=WHITE, tint=True, trace_scale=1.1,
        note="Notification small icon, for the one notification about Pulse itself\n"
             "    rather than about a monitor. The heartbeat alone, and white because the\n"
             "    status-bar mask tints it flat — colour is the system's to choose, not ours.",
    ),
}


def main():
    for path, spec in ASSETS.items():
        g = trace_geometry(spec["canvas"], spec["fill"])
        body = stroke(
            g["trace"], spec["colour"],
            g["trace_stroke"] * spec.get("trace_scale", 1.0), cap="round",
        )
        header = (
            "<!--\n"
            "    Generated by docs/brand/android_assets.py — edit that, not this.\n\n"
            f"    {spec['note']}\n"
            "-->\n"
        )
        out = os.path.join(RES, path)
        open(out, "w").write(vector(spec["canvas"], body, tint=spec.get("tint", False), header=header))
        print(f"wrote {path}")


if __name__ == "__main__":
    main()
