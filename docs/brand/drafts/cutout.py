#!/usr/bin/env python3
"""The shipped cardiogram, knocked out of a solid bell.

The trace is a real mask cutout rather than a dark shape painted over the plate,
so the hole is genuinely transparent and the mark survives on a light page, on a
launcher wallpaper, and as a monochrome notification glyph.

TRACE is copied verbatim from the mark that ships today
(docs/brand/nightbell-30-ringpulse-icon.svg), so this is the same six points, not
a redraw.
"""
import os
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "cut")
os.makedirs(OUT, exist_ok=True)

INK, BLUE, ROSE = "#0B0E13", "#2F6BFF", "#FF4D57"
TRACE = "M76 256H168L200 172L252 344L284 256H436"
BODY = ("M256 146C198 146 162 208 158 296L134 324V344H378V324L354 296"
        "C350 208 314 146 256 146Z")

# The trace is 360 wide and centred on (256, 258) in its own 512 space. Placing
# it means scaling, then translating its scaled centre onto the target point.
def place(scale, cx=256, cy=256):
    tx = cx - 256 * scale
    ty = cy - 258 * scale
    return f'transform="translate({tx:.1f} {ty:.1f}) scale({scale})"'


def mark(key, crown, clapper_fill, scale, stroke, cy=256, inverted=False):
    """One direction, as a plated icon and a bare transparent mark."""
    keep = (
        f'<path d="{BODY}" fill="#fff"/>'
        f'{crown.replace(BLUE, "#fff").replace(ROSE, "#fff")}'
        f'<circle cx="256" cy="384" r="24" fill="#fff"/>'
    )
    cut = (
        f'<g {place(scale, cy=cy)}>'
        f'<path d="{TRACE}" fill="none" stroke="#000" stroke-width="{stroke}"'
        f' stroke-linecap="round" stroke-linejoin="round"/></g>'
    )
    body = (
        f'<defs><mask id="m{key}" maskUnits="userSpaceOnUse" x="0" y="0"'
        f' width="512" height="512">{keep}{cut}</mask></defs>'
        f'<rect width="512" height="512" fill="{BLUE}" mask="url(#m{key})"/>'
    )
    if clapper_fill != BLUE:
        body += f'<circle cx="256" cy="384" r="24" fill="{clapper_fill}"/>'

    if inverted:
        # blue field, bell knocked out of it, trace left standing in blue
        body = (
            f'<defs><mask id="i{key}" maskUnits="userSpaceOnUse" x="0" y="0"'
            f' width="512" height="512">'
            f'<rect x="92" y="92" width="328" height="328" rx="84" fill="#fff"/>'
            f'<path d="{BODY}" fill="#000"/>{crown.replace(BLUE, "#000")}'
            f'<circle cx="256" cy="384" r="24" fill="#000"/>'
            f'</mask></defs>'
            f'<rect width="512" height="512" fill="{BLUE}" mask="url(#i{key})"/>'
            f'<g {place(scale, cy=cy)}><path d="{TRACE}" fill="none" stroke="{BLUE}"'
            f' stroke-width="{stroke}" stroke-linecap="round"'
            f' stroke-linejoin="round"/></g>'
        )

    for kind, plate in (("icon", f'<rect width="512" height="512" rx="112" fill="{INK}"/>'),
                        ("mark", "")):
        p = os.path.join(OUT, f"{key}-{kind}.svg")
        open(p, "w").write(
            f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512"'
            f' width="512" height="512" role="img" aria-label="Nightbell">'
            f'{plate}{body}</svg>\n'
        )
    return key


ROUND_CROWN = f'<circle cx="256" cy="124" r="19" fill="{BLUE}"/>'
SPIKE_CROWN = (f'<path d="M196 122H226L244 88L266 152L284 122H316" fill="none"'
               f' stroke="{BLUE}" stroke-width="18" stroke-linecap="round"'
               f' stroke-linejoin="round"/>')

VARIANTS = [
    # key,              crown,        clapper, scale, stroke, cy
    ("cut-1-plain",     ROUND_CROWN,  BLUE,    0.44,  44,     258),
    ("cut-2-bold",      ROUND_CROWN,  BLUE,    0.44,  58,     258),
    ("cut-3-thin",      ROUND_CROWN,  BLUE,    0.44,  32,     258),
    ("cut-4-wide",      ROUND_CROWN,  BLUE,    0.52,  46,     262),
    ("cut-5-spike",     SPIKE_CROWN,  BLUE,    0.42,  46,     266),
    ("cut-6-red",       ROUND_CROWN,  ROSE,    0.44,  46,     258),
]

keys = [mark(k, c, f, s, w, cy) for k, c, f, s, w, cy in VARIANTS]
keys.append(mark("cut-7-inverted", ROUND_CROWN, BLUE, 0.40, 40, 262, inverted=True))

font = subprocess.run(["fc-match", "-f", "%{file}", "sans"],
                      capture_output=True, text=True).stdout.strip()

tiles = []
for key in keys:
    icon = os.path.join(OUT, f"{key}-icon.svg")
    big, mid, small = (os.path.join(OUT, f"{key}-{n}.png") for n in (256, 96, 48))
    for px, dst in ((256, big), (96, mid), (48, small)):
        subprocess.run(["rsvg-convert", "-w", str(px), icon, "-o", dst], check=True)
    tile = os.path.join(OUT, f"tile-{key}.png")
    subprocess.run([
        "magick", big,
        "(", mid, "-background", "none", "-gravity", "South", "-extent", "96x256", ")",
        "(", small, "-background", "none", "-gravity", "South", "-extent", "48x256", ")",
        "+append", "-background", "#15181f", "-bordercolor", "#15181f", "-border", "12",
        "-gravity", "South", "-background", "#15181f", "-splice", "0x30",
        "-fill", "#c8d0dc", "-font", font, "-pointsize", "19",
        "-annotate", "+0+5", key, tile,
    ], check=True)
    tiles.append(tile)

sheet = os.path.join(HERE, "bells_cutout.png")
subprocess.run(["magick", "montage", *tiles, "-background", "#15181f",
                "-tile", "2x", "-geometry", "+14+14", sheet], check=True)
print(f"{len(tiles)} cutout variants -> {sheet}")
