#!/usr/bin/env python3
"""The two cutout finalists, proved on both grounds and in the lockup.

A mask cutout is only worth it if the hole really is transparent, so each mark is
rendered over the dark plate colour and over paper white. If the trace vanishes
on white, the mask is wrong and the shape needs a different treatment.
"""
import os
import re
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
CUT = os.path.join(HERE, "cut")
OUT = os.path.join(HERE, "final")
os.makedirs(OUT, exist_ok=True)

INK, PAPER = "#0B0E13", "#FFFFFF"
FONT_FAMILY = "DejaVu Sans, Arial, sans-serif"
CANVAS_H, MARK_H, MARK_X, GAP = 190, 132, 56, 60
FINALISTS = ["cut-1-plain", "cut-7-inverted"]

font = subprocess.run(["fc-match", "-f", "%{file}", "sans"],
                      capture_output=True, text=True).stdout.strip()


def inner(path):
    return re.search(r"<svg[^>]*>(.*)</svg>", open(path).read(), re.S).group(1).strip()


def bbox(frag, px=512):
    probe = os.path.join(OUT, ".p.svg")
    open(probe, "w").write(
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512"'
        f' width="{px}" height="{px}">{frag}</svg>\n'
    )
    png = os.path.join(OUT, ".p.png")
    subprocess.run(["rsvg-convert", "-w", str(px), probe, "-o", png], check=True)
    out = subprocess.run(["magick", png, "-bordercolor", "none", "-border", "1",
                          "-trim", "-format", "%w %h %X %Y", "info:"],
                         capture_output=True, text=True, check=True).stdout
    w, h, x, y = (int(v) for v in re.findall(r"[-+]?\d+", out))
    k = 512 / px
    return x * k, y * k, w * k, h * k


def lockup(frag, ground, word, width):
    bx, by, bw, bh = bbox(frag)
    s = MARK_H / bh
    tx, ty = MARK_X - bx * s, CANVAS_H / 2 - (by + bh / 2) * s
    text_x = round(MARK_X + bw * s + GAP)
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {CANVAS_H}"'
        f' width="{width}" height="{CANVAS_H}" role="img" aria-label="Nightbell">\n'
        f'  <rect width="{width}" height="{CANVAS_H}" fill="{ground}"/>\n'
        f'  <g transform="translate({tx:.1f} {ty:.1f}) scale({s:.4f})">{frag}</g>\n'
        f'  <text x="{text_x}" y="126" font-family="{FONT_FAMILY}" font-weight="bold"'
        f' font-size="110" letter-spacing="-2" fill="{word}">Nightbell</text>\n'
        "</svg>\n"
    )


def right_edge(path, ground):
    png = os.path.join(OUT, ".l.png")
    subprocess.run(["rsvg-convert", "-w", "1200", path, "-o", png], check=True)
    out = subprocess.run(["magick", png, "-background", ground, "-flatten",
                          "-bordercolor", ground, "-border", "1", "-fuzz", "3%",
                          "-trim", "-format", "%w %X", "info:"],
                         capture_output=True, text=True, check=True).stdout
    w, x = (int(v) for v in re.findall(r"[-+]?\d+", out))
    src = int(re.search(r'width="(\d+)"', open(path).read()).group(1))
    return (w + x) * src / 1200


rows = []
for key in FINALISTS:
    frag = inner(os.path.join(CUT, f"{key}-mark.svg"))

    # the bare mark on each ground, at three sizes
    strips = []
    for label, ground in (("on ink", INK), ("on paper", PAPER)):
        cells = []
        for px in (200, 96, 48):
            svg = os.path.join(OUT, f".{key}-{label.replace(' ', '')}.svg")
            open(svg, "w").write(
                f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512"'
                f' width="512" height="512"><rect width="512" height="512"'
                f' fill="{ground}"/>{frag}</svg>\n'
            )
            png = os.path.join(OUT, f"{key}-{label.replace(' ', '')}-{px}.png")
            subprocess.run(["rsvg-convert", "-w", str(px), svg, "-o", png], check=True)
            cells.append(png)
        strip = os.path.join(OUT, f".strip-{key}-{label.replace(' ', '')}.png")
        subprocess.run(["magick", *[c for c in cells], "-background", "#15181f",
                        "-gravity", "South", "+append", strip], check=True)
        strips.append((label, strip))

    for label, ground, word in (("dark", INK, "#F2F5F8"), ("light", PAPER, "#0B0E13")):
        p = os.path.join(OUT, f"{key}-lockup-{label}.svg")
        open(p, "w").write(lockup(frag, ground, word, 1100))
        # measure before reopening: "w" truncates, and the probe reads the file
        width = int(round(right_edge(p, ground) + MARK_X))
        open(p, "w").write(lockup(frag, ground, word, width))
        subprocess.run(["rsvg-convert", "-w", "760", p,
                        "-o", os.path.join(OUT, f"{key}-lockup-{label}.png")], check=True)

    block = os.path.join(OUT, f"block-{key}.png")
    subprocess.run([
        "magick",
        strips[0][1], strips[1][1], "+append",
        "(", os.path.join(OUT, f"{key}-lockup-dark.png"), ")",
        "(", os.path.join(OUT, f"{key}-lockup-light.png"), ")",
        "-background", "#15181f", "-gravity", "West", "-append",
        "-bordercolor", "#15181f", "-border", "14",
        "-gravity", "North", "-background", "#15181f", "-splice", "0x34",
        "-fill", "#e6e9ef", "-font", font, "-pointsize", "22",
        "-annotate", "+6+26", key, block,
    ], check=True)
    rows.append(block)

for junk in os.listdir(OUT):
    if junk.startswith("."):
        os.remove(os.path.join(OUT, junk))

sheet = os.path.join(HERE, "bells_finalists.png")
subprocess.run(["magick", "montage", *rows, "-background", "#0f1116",
                "-tile", "1x", "-geometry", "+16+16", sheet], check=True)
print("->", sheet)
