#!/usr/bin/env python3
"""Generate the app's Android vector drawables from the Ring Pulse brand geometry.

    python3 docs/brand/android_assets.py

The mark exists in five places in the app and each one needs different stroke weights,
a different canvas and a different colour treatment. Deriving them from the numbers in
`pulse-30-ringpulse-icon.svg` rather than scaling by hand is not tidiness: the first
hand-scaled set shipped a Compose mark whose ring gap was half the width of the vectors',
and a legacy icon whose trace vertices were plain wrong. Anything with this much repeated
trigonometry should be computed.

Run it after changing any geometry below, then rebuild.
"""

import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.normpath(os.path.join(HERE, "..", "..", "app", "src", "main", "res"))

# The shipped mark's ring is the app's brand blue, not the exploration's green.
#
# All thirty directions in docs/brand are drawn under one rule — green means working,
# red means the moment it breaks — and direction 30 is green-ringed there. In the app
# that rule now belongs to the *data*: charts, the history strip and the status orbs are
# green because they report on something working. Leaving the mark green too made the
# logo look like another status indicator rather than the app's own identity, so the ring
# takes Aqua and green stays earned rather than decorative.
#
# The trace stays red. It is the one element that means failure, in the mark and
# everywhere else.
BLUE, RED, WHITE = "#2F6BFF", "#FF4D57", "#FFFFFFFF"

# The source drawing, in its own 512-unit space.
MID = 256.0
RING_RADIUS = 142.0
RING_STROKE = 40.0
TRACE_STROKE = 26.0

# Distance from centre to the ring's outer edge.
EXTENT = RING_RADIUS + RING_STROKE / 2  # 162

# The trace. Ends at ±149 so its round caps land exactly on EXTENT.
TRACE = [(107, 256), (168, 256), (200, 172), (252, 344), (284, 256), (405, 256)]

# The ring opens by the width of the drawing's 52-unit casing — twice the trace — so each
# arc stops one whole TRACE_STROKE short of centre-height, leaving half a trace-width of
# clearance either side of the red line. Scale-invariant, so it is computed once.
GAP_DEG = math.degrees(math.asin(TRACE_STROKE / RING_RADIUS))


def geometry(canvas, fill_fraction):
    """Scale the drawing so the ring's outer edge covers `fill_fraction` of `canvas`."""
    c = canvas / 2.0
    scale = (canvas * fill_fraction / 2.0) / EXTENT
    r = RING_RADIUS * scale
    dx, dy = r * math.cos(math.radians(GAP_DEG)), r * math.sin(math.radians(GAP_DEG))
    return dict(
        centre=c,
        radius=r,
        ring_stroke=RING_STROKE * scale,
        trace_stroke=TRACE_STROKE * scale,
        # sweep=0,1 draws clockwise; under 180° so large-arc stays 0.
        lower=f"M{c + dx:.2f},{c + dy:.2f} A{r:.2f},{r:.2f} 0 0 1 {c - dx:.2f},{c + dy:.2f}",
        upper=f"M{c - dx:.2f},{c - dy:.2f} A{r:.2f},{r:.2f} 0 0 1 {c + dx:.2f},{c - dy:.2f}",
        trace=" ".join(
            ("M" if i == 0 else "L") + f"{c + (x - MID) * scale:.2f},{c + (y - MID) * scale:.2f}"
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


def mark(g, ring_colour, trace_colour, ring_scale=1.0, trace_scale=1.0):
    return (
        stroke(g["lower"], ring_colour, g["ring_stroke"] * ring_scale)
        + stroke(g["upper"], ring_colour, g["ring_stroke"] * ring_scale)
        + stroke(g["trace"], trace_colour, g["trace_stroke"] * trace_scale, cap="round")
    )


ASSETS = {
    # The launcher icon. Legacy rather than adaptive, because AdaptiveIconDrawable.draw()
    # fills its layer bitmap with Color.BLACK before compositing and so can never be
    # transparent. No mask to survive, so the mark fills the canvas.
    "drawable/ic_launcher_mark.xml": dict(
        canvas=108, fill=0.93, ring=BLUE, trace=RED,
        note="Launcher icon. Legacy (not adaptive) so the background is genuinely\n"
             "    transparent — see docs/brand/android_assets.py for why adaptive cannot be.",
    ),
    # Kept for whenever a plate is wanted again: inset to the 33-unit radius that every
    # adaptive mask is guaranteed to keep, at the cost of looking smaller.
    "drawable/ic_launcher_foreground.xml": dict(
        canvas=108, fill=0.609, ring=BLUE, trace=RED,
        note="Adaptive-icon foreground, inset inside the guaranteed mask circle.\n"
             "    Unused while the launcher icon is the transparent legacy one.",
    ),
    "drawable/ic_launcher_monochrome.xml": dict(
        canvas=108, fill=0.609, ring=WHITE, trace=WHITE,
        note="Themed-icon silhouette. Only the adaptive format carries this, so it is\n"
             "    unused while the launcher icon is legacy.",
    ),
    "drawable/ic_widget_mark.xml": dict(
        canvas=24, fill=0.92, ring=BLUE, trace=RED,
        note="Widget header mark, drawn at 18dp. Two-colour rather than tinted to the\n"
             "    palette: at that size the blue/red pair is what identifies it.",
    ),
    "drawable/ic_stat_brand.xml": dict(
        canvas=24, fill=0.98, ring=WHITE, trace=WHITE, tint=True,
        ring_scale=0.62, trace_scale=0.72,
        note="Notification small icon, for the one notification about Pulse itself\n"
             "    rather than about a monitor. Strokes are thinned because a status-bar\n"
             "    mask at this size turns the full-weight mark into a blob.",
    ),
}


def main():
    for path, spec in ASSETS.items():
        g = geometry(spec["canvas"], spec["fill"])
        body = mark(
            g, spec["ring"], spec["trace"],
            ring_scale=spec.get("ring_scale", 1.0),
            trace_scale=spec.get("trace_scale", 1.0),
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
    print(f"\nring gap: {GAP_DEG:.4f}deg either side of centre-height")


if __name__ == "__main__":
    main()
