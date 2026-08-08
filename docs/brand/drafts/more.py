#!/usr/bin/env python3
"""Twenty more bell directions, plus the original six, on one contact sheet.

Each tile shows the plated icon at 192 and again at 48, because half of these
ideas only fail at the size a launcher and a notification tray actually use.
"""
import glob
import os
import re
import subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "sheet")
os.makedirs(OUT, exist_ok=True)

INK, BLUE, ROSE, MINT = "#0B0E13", "#2F6BFF", "#FF4D57", "#2FD98A"
PALE = "#7DA2FF"

PLATE = f'<rect width="512" height="512" rx="112" fill="{INK}"/>'
BODY = ("M256 146C198 146 162 208 158 296L134 324V344H378V324L354 296"
        "C350 208 314 146 256 146Z")
CROWN = f'<circle cx="256" cy="124" r="19" fill="{BLUE}"/>'
CLAP = f'<circle cx="256" cy="384" r="24" fill="{BLUE}"/>'
# monoline parts
M_DOME = ("M166 302C158 222 194 152 256 152C318 152 354 222 346 302")
M_RIM = "M132 318H380"
M_CROWN = "M238 128C238 110 274 110 274 128"


def stroke(d, w=32, c=BLUE, cap="round", join="round"):
    return (f'<path d="{d}" fill="none" stroke="{c}" stroke-width="{w}" '
            f'stroke-linecap="{cap}" stroke-linejoin="{join}"/>')


D = {}

D["g-swing"] = (
    f'<g transform="rotate(-14 256 130)">{CROWN}'
    f'<path d="{BODY}" fill="{BLUE}"/></g>'
    f'<circle cx="316" cy="392" r="22" fill="{BLUE}"/>'
    + stroke("M104 216C86 250 84 292 98 328", 18)
    + stroke("M150 196C136 224 134 258 144 288", 14)
)

D["h-stepped"] = (
    f'<rect x="240" y="112" width="32" height="30" fill="{BLUE}"/>'
    f'<path d="M196 344V292H214V246H236V206H276V246H298V292H316V344Z" fill="{BLUE}"/>'
    f'<rect x="140" y="316" width="232" height="30" fill="{BLUE}"/>'
    f'<rect x="238" y="374" width="36" height="34" fill="{BLUE}"/>'
)

D["i-knockout"] = (
    f'<rect x="92" y="92" width="328" height="328" rx="84" fill="{BLUE}"/>'
    f'<circle cx="256" cy="140" r="17" fill="{INK}"/>'
    f'<path d="M256 160C204 160 172 216 168 294L148 318V336H364V318L344 294'
    f'C340 216 308 160 256 160Z" fill="{INK}"/>'
    f'<circle cx="256" cy="372" r="21" fill="{INK}"/>'
)

D["j-bars"] = (
    '<defs><clipPath id="jb">'
    f'<path d="{BODY}"/><circle cx="256" cy="124" r="19"/>'
    '</clipPath></defs>'
    f'<g clip-path="url(#jb)">'
    + "".join(
        f'<rect x="120" y="{y}" width="272" height="20" fill="{BLUE}"/>'
        for y in range(104, 348, 32)
    )
    + "</g>"
    + f'<circle cx="256" cy="384" r="24" fill="{BLUE}"/>'
)

D["k-waves"] = (
    stroke(M_CROWN, 20) + stroke(M_DOME, 32) + stroke(M_RIM, 32)
    + f'<circle cx="256" cy="374" r="20" fill="{BLUE}"/>'
    + stroke("M104 208C82 250 82 300 102 340", 18)
    + stroke("M408 208C430 250 430 300 410 340", 18)
)

D["l-sliced"] = (
    CROWN
    + f'<path d="{BODY}" fill="{BLUE}"/>'
    + f'<rect x="120" y="238" width="272" height="22" fill="{INK}"/>'
    + CLAP
)

D["m-terminal"] = (
    stroke("M204 130C204 112 240 112 240 130", 18)
    + stroke("M140 292C132 218 166 154 222 154C278 154 312 218 304 292", 30)
    + stroke("M108 308H336", 30)
    + f'<circle cx="222" cy="362" r="19" fill="{BLUE}"/>'
    + f'<rect x="366" y="238" width="38" height="76" fill="{BLUE}"/>'
)

D["n-reduced"] = (
    stroke("M162 300C154 216 192 148 256 148C320 148 358 216 350 300", 40)
    + stroke("M126 318H386", 40)
)

D["o-squircle"] = (
    f'<rect x="238" y="108" width="36" height="30" rx="14" fill="{BLUE}"/>'
    f'<path d="M256 142C196 142 172 190 172 260V300L142 326V346H370V326L340 300'
    f'V260C340 190 316 142 256 142Z" fill="{BLUE}"/>'
    + CLAP
)

D["p-twostroke"] = (
    stroke("M170 296C164 218 198 156 256 156C314 156 348 218 342 296", 22)
    + stroke("M138 312H374", 22)
)

D["q-hanging"] = (
    stroke("M126 138H386", 22)
    + stroke("M256 150V178", 16)
    + f'<path d="M256 186C210 186 182 236 179 306L160 328V344H352V328L333 306'
    f'C330 236 302 186 256 186Z" fill="{BLUE}"/>'
    + f'<circle cx="256" cy="378" r="20" fill="{BLUE}"/>'
)

D["r-handbell"] = (
    stroke("M256 100V150", 20)
    + f'<circle cx="256" cy="96" r="22" fill="none" stroke="{BLUE}" stroke-width="20"/>'
    + f'<path d="M256 162C204 162 174 220 170 300L146 328V348H366V328L342 300'
    f'C338 220 308 162 256 162Z" fill="{BLUE}"/>'
    + f'<circle cx="256" cy="386" r="22" fill="{BLUE}"/>'
)

D["s-tower"] = (
    stroke("M158 372V196C158 140 200 108 256 108C312 108 354 140 354 196V372", 30, cap="butt")
    + stroke("M132 388H380", 30)
    + f'<path d="M256 186C230 186 216 214 214 254L204 266V276H308V266L298 254'
    f'C296 214 282 186 256 186Z" fill="{BLUE}"/>'
    + f'<circle cx="256" cy="298" r="14" fill="{BLUE}"/>'
)

D["t-spikecrown"] = (
    stroke("M196 122H226L244 88L266 152L284 122H316", 18)
    + f'<path d="M256 168C200 168 166 226 162 308L138 336V354H374V336L350 308'
    f'C346 226 312 168 256 168Z" fill="{BLUE}"/>'
    + f'<circle cx="256" cy="392" r="22" fill="{BLUE}"/>'
)

D["u-onestroke"] = (
    stroke("M118 328H160C150 236 186 158 256 158C326 158 362 236 352 328H394", 30)
)

D["v-moonabove"] = (
    f'<path d="M232 78A64 64 0 1 0 232 190A50 50 0 1 1 232 78Z" fill="{BLUE}"/>'
    f'<path d="M256 216C206 216 176 268 172 336L150 360V378H362V360L340 336'
    f'C336 268 306 216 256 216Z" fill="{BLUE}"/>'
    + f'<circle cx="256" cy="410" r="20" fill="{BLUE}"/>'
)

D["w-monogram"] = (
    f'<circle cx="256" cy="112" r="18" fill="{BLUE}"/>'
    f'<path d="M166 158H206L306 296V158H346V354H306L206 216V354H166Z" fill="{BLUE}"/>'
)

D["x-gong"] = (
    stroke("M140 128H372", 20)
    + stroke("M180 138V166", 14) + stroke("M332 138V166", 14)
    + f'<circle cx="256" cy="272" r="102" fill="none" stroke="{BLUE}" stroke-width="30"/>'
    + f'<circle cx="256" cy="272" r="26" fill="{BLUE}"/>'
    + stroke("M382 348L326 306", 20)
)

D["y-duotone"] = (
    f'<circle cx="256" cy="124" r="19" fill="{PALE}"/>'
    f'<path d="{BODY}" fill="{BLUE}"/>'
    f'<rect x="134" y="322" width="244" height="22" fill="{PALE}"/>'
    f'<circle cx="256" cy="384" r="24" fill="{PALE}"/>'
)

D["z-klaxon"] = (
    f'<path d="M120 226H176L306 140V376L176 290H120Z" fill="{BLUE}"/>'
    + stroke("M352 200C376 232 376 286 352 318", 20)
    + stroke("M394 168C430 216 430 302 394 350", 16)
)


def write_icon(key, inner):
    p = os.path.join(OUT, f"bell-{key}-icon.svg")
    open(p, "w").write(
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="512"'
        f' height="512" role="img" aria-label="Nightbell">{PLATE}{inner}</svg>\n'
    )
    return p


tiles = []
# the original six first, then the twenty new ones
originals = sorted(glob.glob(os.path.join(HERE, "bell-*-icon.svg")))
entries = [(os.path.basename(p)[5:-9], p) for p in originals]
entries += [(k, write_icon(k, v)) for k, v in D.items()]

font = subprocess.run(["fc-match", "-f", "%{file}", "sans"],
                      capture_output=True, text=True).stdout.strip()

for key, path in entries:
    big = os.path.join(OUT, f"{key}-192.png")
    small = os.path.join(OUT, f"{key}-48.png")
    subprocess.run(["rsvg-convert", "-w", "192", path, "-o", big], check=True)
    subprocess.run(["rsvg-convert", "-w", "48", path, "-o", small], check=True)
    tile = os.path.join(OUT, f"tile-{key}.png")
    subprocess.run([
        "magick", big, "(", small, "-background", "none", "-gravity", "South",
        "-extent", "48x192", ")", "+append", "-background", "#15181f",
        "-bordercolor", "#15181f", "-border", "10",
        "-gravity", "South", "-background", "#15181f", "-splice", "0x26",
        "-fill", "#c8d0dc", "-font", font, "-pointsize", "17",
        "-annotate", "+0+4", key, tile,
    ], check=True)
    tiles.append(tile)

sheet = os.path.join(HERE, "bells_all.png")
subprocess.run(["magick", "montage", *tiles, "-background", "#15181f",
                "-tile", "5x", "-geometry", "+12+12", sheet], check=True)
print(f"{len(tiles)} directions -> {sheet}")
