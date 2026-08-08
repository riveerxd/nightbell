#!/usr/bin/env python3
"""Draft sheet: for each bell direction, an app icon, a bare small mark and a full lockup.

Sizes are rendered at the ones that actually decide anything: 512 for the store
listing, 192 for the launcher, 64 and 32 for a favicon and a notification tray.
Lockup width is measured off the rendered ink twice rather than guessed, the same
way docs/brand/build.py sizes its own.
"""
import glob
import os
import re
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
os.makedirs(OUT, exist_ok=True)

PLATE = re.compile(r'<rect width="512" height="512" rx="112" fill="[^"]+"/>\s*')
INK = "#0B0E13"
WORD = "#F2F5F8"
FONT = "DejaVu Sans, Arial, sans-serif"
CANVAS_H = 190
MARK_H = 132          # matches the mark height in the current README logo
MARK_X = 56
GAP = 60


def inner(path):
    """Everything inside the svg element, and the same with the plate removed."""
    src = open(path).read()
    body = re.search(r"<svg[^>]*>(.*)</svg>", src, re.S).group(1)
    return body.strip(), PLATE.sub("", body).strip()


def ink_bbox(svg_text, px=512):
    """Ink bounds of a 512-space fragment, in 512 units."""
    tmp = os.path.join(OUT, ".probe.svg")
    open(tmp, "w").write(
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" '
        f'width="{px}" height="{px}">{svg_text}</svg>\n'
    )
    png = os.path.join(OUT, ".probe.png")
    subprocess.run(["rsvg-convert", "-w", str(px), tmp, "-o", png], check=True)
    out = subprocess.run(
        ["magick", png, "-format", "%w %h %X %Y", "info:"],
        capture_output=True, text=True, check=True,
    ).stdout
    # trim against transparency
    out = subprocess.run(
        ["magick", png, "-bordercolor", "none", "-border", "1", "-trim",
         "-format", "%w %h %X %Y", "info:"],
        capture_output=True, text=True, check=True,
    ).stdout
    w, h, x, y = (int(v) for v in re.findall(r"[-+]?\d+", out))
    k = 512 / px
    return x * k, y * k, w * k, h * k


def lockup(bare, canvas_w):
    bx, by, bw, bh = ink_bbox(bare)
    s = MARK_H / bh
    tx = MARK_X - bx * s
    ty = CANVAS_H / 2 - (by + bh / 2) * s
    text_x = round(MARK_X + bw * s + GAP)
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {canvas_w} {CANVAS_H}"'
        f' width="{canvas_w}" height="{CANVAS_H}" role="img" aria-label="Nightbell">\n'
        f'  <rect width="{canvas_w}" height="{CANVAS_H}" fill="{INK}"/>\n'
        f'  <g transform="translate({tx:.1f} {ty:.1f}) scale({s:.4f})">{bare}</g>\n'
        f'  <text x="{text_x}" y="126" font-family="{FONT}" font-weight="bold"'
        f' font-size="110" letter-spacing="-2" fill="{WORD}">Nightbell</text>\n'
        "</svg>\n"
    )


def measured_right(path):
    png = os.path.join(OUT, ".lock.png")
    subprocess.run(["rsvg-convert", "-w", "1200", path, "-o", png], check=True)
    out = subprocess.run(
        ["magick", png, "-background", INK, "-flatten", "-bordercolor", INK,
         "-border", "1", "-fuzz", "3%", "-trim", "-format", "%w %X", "info:"],
        capture_output=True, text=True, check=True,
    ).stdout
    w, x = (int(v) for v in re.findall(r"[-+]?\d+", out))
    src_w = int(re.search(r'width="(\d+)"', open(path).read()).group(1))
    return (w + x) * src_w / 1200


rows = []
for icon in sorted(glob.glob(os.path.join(HERE, "bell-*-icon.svg"))):
    key = os.path.basename(icon).replace("-icon.svg", "")
    plated, bare = inner(icon)

    # app icon, plated
    for px in (512, 192, 64, 32):
        subprocess.run(["rsvg-convert", "-w", str(px), icon,
                        "-o", os.path.join(OUT, f"{key}-app-{px}.png")], check=True)

    # small mark, no plate, transparent
    mark_svg = os.path.join(OUT, f"{key}-mark.svg")
    open(mark_svg, "w").write(
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="512"'
        f' height="512" role="img" aria-label="Nightbell">{bare}</svg>\n'
    )
    for px in (256, 64, 32):
        subprocess.run(["rsvg-convert", "-w", str(px), mark_svg,
                        "-o", os.path.join(OUT, f"{key}-mark-{px}.png")], check=True)

    # lockup, width measured then tightened to match the left margin
    lock_svg = os.path.join(OUT, f"{key}-lockup.svg")
    open(lock_svg, "w").write(lockup(bare, 1100))
    right = measured_right(lock_svg)
    open(lock_svg, "w").write(lockup(bare, int(round(right + MARK_X))))
    subprocess.run(["rsvg-convert", "-w", "900", lock_svg,
                    "-o", os.path.join(OUT, f"{key}-lockup.png")], check=True)

    rows.append(key)

for junk in (".probe.svg", ".probe.png", ".lock.png"):
    p = os.path.join(OUT, junk)
    if os.path.exists(p):
        os.remove(p)

print("built:", ", ".join(rows))
