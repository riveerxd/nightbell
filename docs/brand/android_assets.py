#!/usr/bin/env python3
"""Generate the app's Android vector drawables from the Nightbell mark geometry.

    python3 docs/brand/android_assets.py     # needs shapely

The mark is the heartbeat trace knocked out of a bell, and it exists in five
places: the launcher icon, an adaptive foreground, a themed silhouette, the
widget header and the notification small icon. Each needs a different canvas, a
different colour and a different amount of detail, so they are computed here from
one set of numbers rather than scaled by hand. An earlier hand-scaled set shipped
a Compose mark and a legacy icon whose trace vertices were plain wrong.

## Why the cutout is a path and not a mask

`nightbell-mark-icon.svg` knocks the trace out with an SVG `<mask>`. Android
vector drawables have no mask, and faking the hole by stroking the trace in the
plate colour would make it opaque: the themed-icon and status-bar layers are
tinted from the alpha channel, so a painted-on hole would simply vanish and the
bell would go solid.

So the hole is a real subpath. Shapely buffers the trace polyline into the
outline of its own stroke, that outline is emitted as a second subpath inside the
bell, and `android:fillType="evenOdd"` turns the enclosed region into a hole.
Shapely is a build-time dependency of this script only; nothing ships with it.

## The small canvases get a wider slot, not a missing one

These two shipped solid for one release on the claim that "at 18dp the slot is
under two pixels". That was the dp figure read as pixels. The widget header is
drawn at 18dp, and a 1.01dp slot is 2px at xhdpi, 3px at xxhdpi and 4px at
xxxhdpi, which is a legible line on every density this app targets. The mark
looked wrong precisely because it disagreed with the launcher icon sitting next
to it.

So all five carry the trace. The two 24dp canvases widen it by `trace_boost`,
because the hole has to survive a 1x density and the antialiasing of a very small
render, and a slot that closes up is worse than one slightly heavier than the
master. The launcher canvases use the master proportions unchanged.

Run it after changing any geometry below, then rebuild.
"""

import os

from shapely.geometry import LineString

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.normpath(os.path.join(HERE, "..", "..", "app", "src", "main", "res"))

# The mark is the brand blue. White is for the two places it is a flat silhouette:
# the status-bar icon and the themed-icon monochrome layer, both masked and tinted
# by the system, where colour is the system's choice and not ours.
BLUE, WHITE = "#2F6BFF", "#FFFFFFFF"

# The bell, in its own 512-unit space, as absolute segments so it can be
# transformed without parsing anything.
BELL = [
    ("M", [(256, 146)]),
    ("C", [(198, 146), (162, 208), (158, 296)]),
    ("L", [(134, 324)]),
    ("L", [(134, 344)]),
    ("L", [(378, 344)]),
    ("L", [(378, 324)]),
    ("L", [(354, 296)]),
    ("C", [(350, 208), (314, 146), (256, 146)]),
    ("Z", []),
]
CROWN = (256.0, 124.0, 19.0)
CLAPPER = (256.0, 384.0, 24.0)

# The trace: the same six points the Pulse mark drew, scaled to 0.42 and centred
# inside the bell. Placed rather than redrawn, so the old identity is literally
# the same drawing.
TRACE_SRC = [(76, 256), (168, 256), (200, 172), (252, 344), (284, 256), (436, 256)]
TRACE_SCALE = 0.42
TRACE_STROKE = 45.0 * TRACE_SCALE
TRACE_DX, TRACE_DY = 148.48, 149.64
TRACE = [(TRACE_DX + x * TRACE_SCALE, TRACE_DY + y * TRACE_SCALE) for x, y in TRACE_SRC]

# Ink bounds of bell plus crown plus clapper, in the same 512 space.
SRC_X0, SRC_X1 = 134.0, 378.0
SRC_Y0, SRC_Y1 = CROWN[1] - CROWN[2], CLAPPER[1] + CLAPPER[2]


def fitter(canvas, fill):
    """Scale the mark to `fill` of `canvas` and centre it. Height dominates."""
    s = (canvas * fill) / max(SRC_X1 - SRC_X0, SRC_Y1 - SRC_Y0)
    cx, cy = (SRC_X0 + SRC_X1) / 2.0, (SRC_Y0 + SRC_Y1) / 2.0
    half = canvas / 2.0
    return lambda x, y: (half + (x - cx) * s, half + (y - cy) * s), s


def bell_path(pt):
    out = []
    for cmd, coords in BELL:
        if cmd == "Z":
            out.append("Z")
            continue
        out.append(cmd + " ".join(f"{a:.2f},{b:.2f}" for a, b in (pt(x, y) for x, y in coords)))
    return "".join(out)


def circle_path(pt, s, circle):
    cx, cy, r = circle
    x, y = pt(cx, cy)
    rr = r * s
    return (
        f"M{x - rr:.2f},{y:.2f}"
        f"A{rr:.2f},{rr:.2f} 0 1,0 {x + rr:.2f},{y:.2f}"
        f"A{rr:.2f},{rr:.2f} 0 1,0 {x - rr:.2f},{y:.2f}Z"
    )


def trace_hole(pt, s, boost=1.0):
    """The trace's own stroke outline, as a closed subpath."""
    poly = LineString(TRACE).buffer(
        TRACE_STROKE * boost / 2.0, cap_style="round", join_style="round", resolution=16
    )
    ring = poly.exterior
    pts = [pt(x, y) for x, y in ring.coords]
    return "M" + "L".join(f"{x:.2f},{y:.2f}" for x, y in pts) + "Z"


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


def fill_path(path, colour, even_odd):
    fill_type = '\n        android:fillType="evenOdd"' if even_odd else ""
    return (
        "    <path\n"
        f'        android:pathData="{path}"\n'
        f'        android:fillColor="{colour}"{fill_type} />\n'
    )


ASSETS = {
    # Legacy rather than adaptive, because AdaptiveIconDrawable.draw() fills its
    # layer bitmap with Color.BLACK before compositing and so can never be
    # transparent. No mask to survive, so the mark fills the canvas.
    "drawable/ic_launcher_mark.xml": dict(
        canvas=108, fill=0.92, colour=BLUE, cutout=True,
        note="Launcher icon. Legacy (not adaptive) so the background is genuinely\n"
             "    transparent — see docs/brand/android_assets.py for why adaptive cannot be.",
    ),
    "drawable/ic_launcher_foreground.xml": dict(
        canvas=108, fill=0.62, colour=BLUE, cutout=True,
        note="Adaptive-icon foreground, inset inside the guaranteed mask circle.\n"
             "    Unused while the launcher icon is the transparent legacy one.",
    ),
    "drawable/ic_launcher_monochrome.xml": dict(
        canvas=108, fill=0.62, colour=WHITE, cutout=True,
        note="Themed-icon silhouette. The trace is a real hole in the alpha channel,\n"
             "    so it survives the system tinting this layer flat.",
    ),
    # The system splash icon, API 31+. Its canvas is 288dp and the platform draws
    # the drawable into it whole, so `fill` here is not "how much of an icon
    # tile" but "how big the bell is on the screen": 0.32 of 288 lands it at
    # about 92dp, which is the size NightbellSplash starts its own bell at. The
    # two screens then line up and the handoff stops being a jump.
    "drawable/ic_splash_mark.xml": dict(
        canvas=288, fill=0.32, colour=BLUE, cutout=True,
        note="System splash icon (values-v31). Sized to match the first frame of\n"
             "    NightbellSplash so the platform screen and ours are one opening.",
    ),
    "drawable/ic_widget_mark.xml": dict(
        canvas=24, fill=0.90, colour=BLUE, cutout=True, trace_boost=1.35,
        note="Widget header mark, drawn at 18dp. The trace is widened by a third so\n"
             "    the slot stays open at 1x; it is 2 to 4 px on every density above that.",
    ),
    "drawable/ic_stat_brand.xml": dict(
        canvas=24, fill=0.90, colour=WHITE, tint=True, cutout=True, trace_boost=1.35,
        note="Notification small icon, for the one notification about Nightbell itself\n"
             "    rather than about a monitor. White because the status-bar mask tints it\n"
             "    flat — colour is the system's to choose, not ours — which is exactly why\n"
             "    the trace has to be a real hole in the alpha rather than a painted line.",
    ),
}


def main():
    for path, spec in ASSETS.items():
        pt, s = fitter(spec["canvas"], spec["fill"])
        subpaths = [bell_path(pt), circle_path(pt, s, CROWN), circle_path(pt, s, CLAPPER)]
        if spec["cutout"]:
            subpaths.append(trace_hole(pt, s, spec.get("trace_boost", 1.0)))
        body = fill_path("".join(subpaths), spec["colour"], spec["cutout"])
        header = (
            "<!--\n"
            "    Generated by docs/brand/android_assets.py — edit that, not this.\n\n"
            f"    {spec['note']}\n"
            "-->\n"
        )
        out = os.path.join(RES, path)
        open(out, "w").write(
            vector(spec["canvas"], body, tint=spec.get("tint", False), header=header)
        )
        print(f"wrote {path}")


if __name__ == "__main__":
    main()
