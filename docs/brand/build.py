#!/usr/bin/env python3
"""Regenerate the Pulse lockups, the PNG exports and logos.html from the 30 icon SVGs.

The icon SVGs are the only hand-authored source. Everything else derives from them:

    python3 docs/brand/build.py            # lockups + logos.html
    python3 docs/brand/build.py --png      # also re-export png/ (needs rsvg-convert)

Lockup placement is driven by each mark's real ink bounds, measured by rendering the
mark and trimming (needs rsvg-convert + ImageMagick). Results cache to bbox.json, so
a machine without either tool can still rebuild from the cache.
"""

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))

# id, slug, name, tagline, rationale, [strength, neutral note, weakness]
CONCEPTS = [
    ("01", "cardiogram", "Cardiogram", "Flatline, then a red spike",
     "The literal read, executed properly. Green holds the baseline; red owns the one event that "
     "matters. Nobody has to be told what it means.",
     ["Safest launcher icon", "Reads at 24px", "Least distinctive"]),
    ("02", "ping", "Ping", "A check leaving the phone",
     "Concentric rings around a red core: the outbound check, and the thing being watched. Strongest "
     "silhouette in the set — it survives any size and any background.",
     ["Best silhouette", "Great favicon", "Generic without the red core"]),
    ("03", "monogram", "Monogram", "P with the ping in its counter",
     "A capital P whose counter holds the alert dot. Works as a bare glyph — in body text, a footer, "
     "a notification tray, a sticker.",
     ["Scales to 16px", "Works unaccompanied", "Says least about uptime"]),
    ("04", "redcard", "Red Card", "The page you cannot swipe away",
     "Leads with the actual differentiator: an urgent page that behaves like an incoming call. The "
     "inverted plate makes it the loudest tile in any launcher grid.",
     ["Loudest in a grid", "On-message", "Red plate spends the alert colour"]),
    ("05", "bars", "Bars", "Latency, with the outage in red",
     "The detail-screen chart abstracted to five bars. Clean and confident, but it borrows the visual "
     "language of audio waveforms — that ambiguity is the cost.",
     ["Ties to the charts", "Very legible small", "Reads as audio"]),
    ("06", "nightwatch", "Nightwatch", "For the 3am promise",
     "A crescent broken by a pulse. The only mark about <em>when</em> Pulse matters rather than what "
     "it measures, and the old-style serif keeps it from looking like a sleep tracker.",
     ["Most memorable", "Owns the 3am story", "Least literal"]),
    ("07", "shield", "Shield", "Assurance framing",
     "Green shield, red pulse inside. Instantly parsed by anyone who works in ops, which is exactly "
     "the problem — half of monitoring and most of security already uses this shape.",
     ["Immediately understood", "Enterprise read", "Crowded category"]),
    ("08", "board", "Board", "Nine monitors, one down",
     "Encodes the dashboard itself: a grid of healthy checks with a single red one ringed. A literal "
     "picture of what the app does, and one of the softest at small sizes.",
     ["Explains the product", "Good in-app header", "Soft at 40px"]),
    ("09", "sweep", "Sweep", "Actively checking",
     "Radar. Communicates <em>continuous</em> checking rather than a state, which matters for an app "
     "whose pitch is that the phone in your pocket does the polling.",
     ["Conveys motion", "Animates naturally", "Busy centre"]),
    ("10", "gauge", "Gauge", "Uptime as a ring, downtime as the notch",
     "A near-complete ring with a red segment for the minutes you lost. Abstract, and the one that "
     "behaves most like a brand rather than an illustration.",
     ["Most brand-like", "Strong at any size", "Needs the wordmark"]),
    ("11", "tower", "Tower", "Transmitting, not measuring",
     "A mast with the ping leaving the top. Frames Pulse as the thing doing the reaching out, which "
     "is literally true — the phone is the one making the request.",
     ["Clear category signal", "Good above 64px", "Reads telecom"]),
    ("12", "minute", "Minute", "The minute you lost",
     "A clock with a red minute hand. Points at downtime as a duration rather than an event, and it "
     "is the only mark that connects to quiet hours and cadence.",
     ["Ties to quiet hours", "Universally read", "Overloaded shape on a phone"]),
    ("13", "beeper", "Beeper", "The device this replaces",
     "The pager, with a live trace on its screen and a red LED. The README calls urgent alerts pages; "
     "this is that word drawn. Most personality of the thirty.",
     ["Most characterful", "Excellent in docs", "Needs 96px to land"]),
    ("14", "pixel", "Pixel", "Monogram on a 3 × 6 grid",
     "A P built from pixels, its counter a single red cell. The hard grid gives the identity a "
     "systems-tool voice that the smooth marks do not have.",
     ["Distinctive letterform", "Scales cleanly", "Fights round icon masks"]),
    ("15", "slab", "Slab", "Brutalist P, red foot",
     "Square terminals, no curves, no metaphor. Pairs with a heavy wordmark better than anything else "
     "here and would survive being screen-printed on a sticker.",
     ["Best wordmark pairing", "Prints well", "No uptime metaphor at all"]),
    ("16", "step", "Step", "Uptime as a staircase",
     "Green steps climbing, one red drop, then the climb resumes. The most narrative mark in the set "
     "and the one that fits a square plate worst.",
     ["Reads as data", "A story in one glyph", "Awkward in a square"]),
    ("17", "bell", "Bell", "The alarm, and its clapper",
     "The clapper carries the red, so the alert colour sits on the part that actually makes noise. "
     "Clean, classical, and colliding with every notification icon Android ships.",
     ["Instantly understood", "Pairs with anything", "Collides with system icons"]),
    ("18", "prompt", "Prompt", "For the person who deploys the thing",
     "A shell chevron and a red cursor. Says self-hosted, no account, no third party — the exact "
     "pitch in the README — to the people who care about that.",
     ["Right audience", "Unmistakably technical", "Narrow appeal"]),
    ("19", "eye", "Eye", "The watcher",
     "Lens, iris, red pupil. The strongest one-glance metaphor for watching, weighed against the fact "
     "that an eye reads as surveillance before it reads as monitoring.",
     ["Strong metaphor", "Great silhouette", "Surveillance overtones"]),
    ("20", "stack", "Stack", "Three services, top one down",
     "Slabs with status LEDs. Speaks fluent infrastructure and needs those LEDs to stop three bars "
     "from reading as a hamburger menu.",
     ["Infra-native", "Bold at any size", "Close to a menu icon"]),
    ("21", "sine", "Sine", "Rhythm, then the anomaly",
     "A steady wave that breaks into a red spike and flatlines. The best storytelling of the thirty: "
     "you can read the whole incident left to right.",
     ["Best storytelling", "Elegant line", "Needs the full width"]),
    ("22", "segments", "Segments", "Six monitors, one red",
     "A ring cut into discrete segments, so the mark encodes that you are watching several things "
     "rather than one. Geometric, calm, systematic.",
     ["Encodes the count", "Clean geometry", "Gaps close up when small"]),
    ("23", "bolt", "Bolt", "The surge that breaks the line",
     "A red bolt cutting a green baseline. The most kinetic mark here by a distance, and the one most "
     "likely to be read as power or speed instead of uptime.",
     ["Most energetic", "Unmissable red", "Says power, not uptime"]),
    ("24", "bracket", "Bracket", "The thing being watched, in brackets",
     "Typographic and quiet: two brackets and the subject between them. It will age better than any "
     "illustrative mark here, and it says the least on its own.",
     ["Ages well", "Perfect favicon", "Anonymous unaccompanied"]),
    ("25", "fan", "Fan", "Latency as a gauge",
     "Bars fanned across a dial with the last one red. Reads like an instrument rather than a picture, "
     "which suits an app that is mostly charts.",
     ["Feels like an instrument", "Good at 64px", "Implies a reading to interpret"]),
    ("26", "orbit", "Orbit", "A check going round",
     "Green core, red satellite mid-orbit with its trail behind it. The obvious candidate if the icon "
     "should ever animate while a check runs.",
     ["Best animation hook", "Distinct silhouette", "Reads as space"]),
    ("27", "rows", "Rows", "The list, with one row down",
     "The dashboard's own row treatment, reduced to three entries. The most immediately legible "
     "picture of the product, and the least logo-like thing in the set.",
     ["Obviously the product", "Good in-app", "Least logo-like"]),
    ("28", "crosshair", "Crosshair", "What it is pointed at",
     "Ring, ticks, red centre. One of the two or three strongest silhouettes here; the cost is that a "
     "crosshair carries targeting associations you cannot fully shake.",
     ["Very strong silhouette", "Works tiny", "Targeting overtones"]),
    ("29", "halftone", "Halftone", "The trace, in beads",
     "The cardiogram rendered as overlapping dots, the peak swollen and red. The most crafted mark "
     "here and the one that loses the most detail when shrunk.",
     ["Most crafted", "Unusual texture", "Beads merge below 48px"]),
    ("30", "ringpulse", "Ring Pulse", "The trace cutting through the ring",
     "Badge construction: containment plus the event, with the trace punching a clean gap through the "
     "ring rather than sitting on top of it. The most finished of the badge forms.",
     ["Best badge form", "Strong at 40px", "Busiest centre"]),
]

# The plate is part of the mark for 04, so its lockup keeps the container.
PLATED_LOCKUP = {"04"}

# Each mark gets typography matched to its character, not one house style applied thirty times.
FAMILIES = {
    "sans":  "Inter, 'Helvetica Neue', Helvetica, Arial, sans-serif",
    "serif": "'Iowan Old Style', Georgia, 'Times New Roman', serif",
    "mono":  "'JetBrains Mono', 'SF Mono', Menlo, Consolas, monospace",
}
WORDMARK = {
    "01": dict(text="Pulse", size=142, weight="700", tracking="-5", family="sans"),
    "02": dict(text="PULSE", size=98,  weight="600", tracking="16", family="sans"),
    "03": dict(text="Pulse", size=142, weight="700", tracking="-4", family="sans"),
    "04": dict(text="PULSE", size=106, weight="800", tracking="2",  family="sans"),
    "05": dict(text="Pulse", size=142, weight="700", tracking="-3", family="sans"),
    "06": dict(text="Pulse", size=132, weight="400", tracking="6",  family="serif"),
    "07": dict(text="PULSE", size=98,  weight="700", tracking="10", family="sans"),
    "08": dict(text="PULSE", size=88,  weight="500", tracking="14", family="mono"),
    "09": dict(text="pulse", size=124, weight="500", tracking="2",  family="mono"),
    "10": dict(text="Pulse", size=142, weight="800", tracking="-6", family="sans"),
    "11": dict(text="Pulse", size=138, weight="700", tracking="-3", family="sans"),
    "12": dict(text="PULSE", size=88,  weight="500", tracking="12", family="mono"),
    "13": dict(text="PULSE", size=104, weight="800", tracking="4",  family="sans"),
    "14": dict(text="PULSE", size=90,  weight="700", tracking="8",  family="mono"),
    "15": dict(text="Pulse", size=146, weight="800", tracking="-6", family="sans"),
    "16": dict(text="PULSE", size=86,  weight="400", tracking="10", family="mono"),
    "17": dict(text="Pulse", size=138, weight="700", tracking="-4", family="sans"),
    "18": dict(text="pulse", size=124, weight="500", tracking="0",  family="mono"),
    "19": dict(text="Pulse", size=132, weight="400", tracking="4",  family="serif"),
    "20": dict(text="PULSE", size=100, weight="600", tracking="2",  family="sans"),
    "21": dict(text="PULSE", size=96,  weight="300", tracking="18", family="sans"),
    "22": dict(text="PULSE", size=88,  weight="500", tracking="12", family="mono"),
    "23": dict(text="PULSE", size=110, weight="800", tracking="0",  family="sans"),
    "24": dict(text="pulse", size=124, weight="600", tracking="2",  family="mono"),
    "25": dict(text="PULSE", size=98,  weight="500", tracking="12", family="sans"),
    "26": dict(text="Pulse", size=132, weight="400", tracking="2",  family="serif"),
    "27": dict(text="Pulse", size=136, weight="600", tracking="1",  family="sans"),
    "28": dict(text="PULSE", size=88,  weight="500", tracking="16", family="mono"),
    "29": dict(text="Pulse", size=142, weight="700", tracking="-4", family="sans"),
    "30": dict(text="Pulse", size=142, weight="800", tracking="-5", family="sans"),
}

PLATE_RE = r'<rect width="512" height="512" rx="112" fill="([^"]+)"/>'

# Lockup geometry: the mark is fitted into this box, then the wordmark follows it.
CANVAS_W, CANVAS_H = 880, 320
MARK_X, MARK_BOX_W, MARK_BOX_H = 40, 300, 252
WORD_GAP = 72


def icon_path(cid, slug):
    return os.path.join(HERE, f"pulse-{cid}-{slug}-icon.svg")


def read_icon(cid, slug):
    src = open(icon_path(cid, slug)).read()
    inner = re.search(r"<svg[^>]*>(.*)</svg>", src, re.S).group(1).strip()
    plate = re.search(PLATE_RE, src).group(1)
    bare = re.sub(PLATE_RE, "", inner, count=1).strip()
    return inner, bare, plate


def measure_bboxes():
    """Ink bounds of each lockup mark, in 512-space. Cached to bbox.json."""
    cache_file = os.path.join(HERE, "bbox.json")
    cache = {}
    if os.path.exists(cache_file):
        cache = json.load(open(cache_file))

    have_tools = shutil.which("rsvg-convert") and (shutil.which("magick") or shutil.which("identify"))
    missing = [c[0] for c in CONCEPTS if c[0] not in cache]
    if missing and not have_tools:
        raise SystemExit(f"no bbox cache for {missing} and rsvg-convert/ImageMagick unavailable")
    if not have_tools:
        return cache

    identify = ["magick", "identify"] if shutil.which("magick") else ["identify"]
    for cid, slug, *_ in CONCEPTS:
        inner, bare, plate = read_icon(cid, slug)
        body = inner if cid in PLATED_LOCKUP else bare
        with tempfile.TemporaryDirectory() as tmp:
            svg, png = os.path.join(tmp, "m.svg"), os.path.join(tmp, "m.png")
            open(svg, "w").write(
                '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" '
                f'width="512" height="512">{body}</svg>')
            subprocess.run(["rsvg-convert", "-w", "512", "-h", "512", svg, "-o", png], check=True)
            geom = subprocess.run(identify + ["-format", "%@", png],
                                  capture_output=True, text=True, check=True).stdout.strip()
        m = re.match(r"(\d+)x(\d+)\+(-?\d+)\+(-?\d+)", geom)
        bw, bh, bx, by = (int(g) for g in m.groups())
        cache[cid] = [bx, by, bw, bh]
    json.dump(cache, open(cache_file, "w"), indent=1, sort_keys=True)
    return cache


def build_lockups(bbox):
    for cid, slug, *_ in CONCEPTS:
        inner, bare, plate = read_icon(cid, slug)
        body = inner if cid in PLATED_LOCKUP else bare
        bx, by, bw, bh = bbox[cid]
        scale = min(MARK_BOX_W / bw, MARK_BOX_H / bh)
        tx = MARK_X - bx * scale
        ty = CANVAS_H / 2 - (by + bh / 2) * scale
        text_x = round(MARK_X + bw * scale + WORD_GAP)
        w = WORDMARK[cid]
        baseline = round(CANVAS_H / 2 + 0.36 * w["size"])
        out = (
            f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {CANVAS_W} {CANVAS_H}"'
            f' width="{CANVAS_W}" height="{CANVAS_H}" role="img" aria-label="Pulse">\n'
            f'  <rect width="{CANVAS_W}" height="{CANVAS_H}" fill="#0B0E13"/>\n'
            f'  <g transform="translate({tx:.1f} {ty:.1f}) scale({scale:.4f})">{body}</g>\n'
            f'  <text x="{text_x}" y="{baseline}" font-family="{FAMILIES[w["family"]]}"'
            f' font-size="{w["size"]}" font-weight="{w["weight"]}"'
            f' letter-spacing="{w["tracking"]}" fill="#F2F5F8">{w["text"]}</text>\n'
            '</svg>\n'
        )
        open(os.path.join(HERE, f"pulse-{cid}-{slug}-lockup.svg"), "w").write(out)


def build_png():
    png = os.path.join(HERE, "png")
    os.makedirs(png, exist_ok=True)
    for cid, slug, *_ in CONCEPTS:
        stem = f"pulse-{cid}-{slug}"
        for width, suffix in ((512, "-512"), (192, "-192")):
            subprocess.run(["rsvg-convert", "-w", str(width), os.path.join(HERE, f"{stem}-icon.svg"),
                            "-o", os.path.join(png, f"{stem}-icon{suffix}.png")], check=True)
        subprocess.run(["rsvg-convert", "-w", "1760", os.path.join(HERE, f"{stem}-lockup.svg"),
                        "-o", os.path.join(png, f"{stem}-lockup.png")], check=True)


SWATCHES = [
    ("#0B0E13", "Ink", "plate, ground"),
    ("#F2F5F8", "Bone", "glyph, wordmark"),
    ("#2FD98A", "Signal", "up, healthy, checking"),
    ("#FF4D57", "Alarm", "down — once per mark"),
    ("#1E2530", "Line", "rules, dividers"),
]

CSS = """
:root {
  --bg:#EEF1EF; --surface:#FFFFFF; --text:#0E1318; --muted:#5B6A70; --line:#D5DCD7;
  --green:#12A265; --red:#D93B45;
  --sans:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif;
  --mono:ui-monospace,"SF Mono","JetBrains Mono",Menlo,Consolas,monospace;
}
/* the dark ground is deliberately lifted off the #0B0E13 plate so the icon's
   rounded-square container stays visible instead of merging into the page */
@media (prefers-color-scheme:dark) {
  :root { --bg:#151B24; --surface:#1B222C; --text:#E7EDF2; --muted:#8794A1; --line:#28313D;
          --green:#2FD98A; --red:#FF4D57; }
}
:root[data-theme="dark"] { --bg:#151B24; --surface:#1B222C; --text:#E7EDF2; --muted:#8794A1;
  --line:#28313D; --green:#2FD98A; --red:#FF4D57; }
:root[data-theme="light"] { --bg:#EEF1EF; --surface:#FFFFFF; --text:#0E1318; --muted:#5B6A70;
  --line:#D5DCD7; --green:#12A265; --red:#D93B45; }

* { box-sizing:border-box; }
body { margin:0; background:var(--bg); color:var(--text); font-family:var(--sans);
       -webkit-font-smoothing:antialiased; font-size:16px; line-height:1.6; }
.wrap { max-width:1120px; margin:0 auto; padding:0 24px 96px; }

.strip { display:flex; flex-wrap:wrap; gap:8px 20px; align-items:center; padding:16px 0;
         border-bottom:1px solid var(--line); font-family:var(--mono);
         font-size:.68rem; letter-spacing:.16em; text-transform:uppercase; color:var(--muted); }
.strip .live { display:inline-flex; align-items:center; gap:8px; color:var(--green); }
.strip .live::before { content:""; width:8px; height:8px; border-radius:50%; background:var(--green); }
.strip .end { margin-left:auto; }

.masthead { padding:72px 0 56px; border-bottom:1px solid var(--line); }
h1 { font-size:clamp(2.5rem,7vw,4.4rem); line-height:1.02; letter-spacing:-.035em;
     font-weight:800; margin:0 0 20px; max-width:22ch; text-wrap:balance; }
h1 em { font-style:normal; color:var(--red); }
.lede { max-width:62ch; margin:0; color:var(--muted); font-size:1.075rem; }
.eyebrow { font-family:var(--mono); font-size:.68rem; letter-spacing:.18em;
           text-transform:uppercase; color:var(--muted); margin:0 0 28px; }

.contact { padding:48px 0; border-bottom:1px solid var(--line); }
.grid { list-style:none; margin:0; padding:0;
        display:grid; grid-template-columns:repeat(auto-fill,minmax(84px,1fr)); gap:18px 16px; }
.grid a { display:block; text-decoration:none; color:var(--muted); }
.grid svg { display:block; width:100%; height:auto; aspect-ratio:1; }
.grid span { display:block; margin-top:7px; font-family:var(--mono); font-size:.62rem;
             letter-spacing:.08em; text-transform:uppercase; }
.grid a:hover span, .grid a:focus-visible span { color:var(--green); }

.system { padding:48px 0; border-bottom:1px solid var(--line); }
.syswrap { display:grid; grid-template-columns:1fr 1fr; gap:48px; align-items:start; }
.syswrap p { margin:0 0 14px; max-width:56ch; color:var(--muted); }
.syswrap p strong { color:var(--text); font-weight:600; }
.pal { list-style:none; margin:0; padding:0; display:grid; gap:0; }
.pal li { display:grid; grid-template-columns:32px 5.5rem auto 1fr; align-items:center; gap:14px;
          padding:10px 0; border-bottom:1px solid var(--line); }
.pal li:last-child { border-bottom:0; }
.swatch { width:32px; height:32px; border-radius:8px; border:1px solid var(--line); }
.pal b { font-weight:600; font-size:.95rem; }
.pal code, .pal em { font-family:var(--mono); font-size:.75rem; color:var(--muted); font-style:normal; }

.concept { display:grid; grid-template-columns:300px 1fr; gap:56px;
           padding:56px 0; border-bottom:1px solid var(--line); align-items:start;
           scroll-margin-top:24px; }
.specimen { position:sticky; top:24px; }
.ic { display:block; }
.ic-xl { width:192px; height:192px; }
.sizes { display:flex; align-items:flex-end; gap:16px; margin-top:24px; }
.ic-md { width:96px; height:96px; }
.ic-sm { width:64px; height:64px; }
.ic-xs { width:40px; height:40px; }
.sizelabel { font-family:var(--mono); font-size:.68rem; letter-spacing:.1em;
             color:var(--muted); margin:14px 0 0; }

.chead { display:grid; grid-template-columns:auto 1fr; gap:4px 16px; align-items:baseline;
         margin-bottom:24px; }
.num { font-family:var(--mono); font-size:.95rem; color:var(--muted); grid-row:span 2; }
.chead h2 { margin:0; font-size:1.65rem; letter-spacing:-.02em; font-weight:700; }
.tag { margin:0; grid-column:2; color:var(--green); font-family:var(--mono);
       font-size:.72rem; letter-spacing:.1em; text-transform:uppercase; }
.lockup { display:block; width:100%; max-width:620px; height:auto; aspect-ratio:880/320;
          border-radius:4px; border:1px solid var(--line); }
.copy { max-width:60ch; margin:24px 0 0; }
.copy em { font-style:italic; }

.chips { list-style:none; display:flex; flex-wrap:wrap; gap:8px; padding:0; margin:22px 0 0; }
.chips li { font-family:var(--mono); font-size:.7rem; letter-spacing:.06em; text-transform:uppercase;
            padding:6px 11px; border:1px solid var(--line); border-radius:999px; color:var(--muted); }
.chips li:first-child { color:var(--green);
            border-color:color-mix(in srgb, var(--green) 45%, transparent); }
.chips li:last-child { color:var(--red);
            border-color:color-mix(in srgb, var(--red) 40%, transparent); }
.files { margin:20px 0 0; display:flex; flex-wrap:wrap; gap:6px 18px;
         font-family:var(--mono); font-size:.72rem; color:var(--muted); }

.notes { padding:56px 0 0; }
.notes h3 { font-size:1.15rem; margin:0 0 12px; letter-spacing:-.01em; }
.notes p { max-width:62ch; color:var(--muted); margin:0 0 26px; }
.notes code { font-family:var(--mono); font-size:.85em; color:var(--text); }
pre { font-family:var(--mono); font-size:.78rem; background:var(--surface); color:var(--text);
      border:1px solid var(--line); border-radius:6px; padding:16px; overflow-x:auto; margin:0 0 26px; }
a { color:var(--green); }
a:focus-visible { outline:2px solid var(--green); outline-offset:3px; }

@media (max-width:860px) {
  .syswrap { grid-template-columns:1fr; gap:32px; }
  .concept { grid-template-columns:1fr; gap:28px; padding:44px 0; }
  .specimen { position:static; }
  .ic-xl { width:140px; height:140px; }
}
"""


def build_html():
    symbols, rows, tiles = [], [], []
    for cid, slug, name, tagline, copy, notes in CONCEPTS:
        icon, _, _ = read_icon(cid, slug)
        lock = open(os.path.join(HERE, f"pulse-{cid}-{slug}-lockup.svg")).read()
        lock_inner = re.search(r"<svg[^>]*>(.*)</svg>", lock, re.S).group(1).strip()
        symbols.append(f'<symbol id="i{cid}" viewBox="0 0 512 512">{icon}</symbol>')
        symbols.append(f'<symbol id="w{cid}" viewBox="0 0 880 320">{lock_inner}</symbol>')
        tiles.append(f'<li><a href="#c{cid}"><svg aria-hidden="true"><use href="#i{cid}"/></svg>'
                     f'<span>{cid} {name}</span></a></li>')
        chips = "".join(f"<li>{c}</li>" for c in notes)
        rows.append(f'''<article class="concept" id="c{cid}">
  <div class="specimen">
    <svg class="ic ic-xl" role="img" aria-label="{name} icon"><use href="#i{cid}"/></svg>
    <div class="sizes">
      <svg class="ic ic-md" aria-hidden="true"><use href="#i{cid}"/></svg>
      <svg class="ic ic-sm" aria-hidden="true"><use href="#i{cid}"/></svg>
      <svg class="ic ic-xs" aria-hidden="true"><use href="#i{cid}"/></svg>
    </div>
    <p class="sizelabel">192 &middot; 96 &middot; 64 &middot; 40 px</p>
  </div>
  <div class="detail">
    <div class="chead">
      <span class="num">{cid}</span>
      <h2>{name}</h2>
      <p class="tag">{tagline}</p>
    </div>
    <svg class="lockup" role="img" aria-label="{name} lockup"><use href="#w{cid}"/></svg>
    <p class="copy">{copy}</p>
    <ul class="chips">{chips}</ul>
    <p class="files"><span>pulse-{cid}-{slug}-icon.svg</span><span>pulse-{cid}-{slug}-lockup.svg</span></p>
  </div>
</article>''')

    sw = "".join(
        f'<li><span class="swatch" style="background:{h}"></span><b>{n}</b><code>{h}</code>'
        f'<em>{u}</em></li>' for h, n, u in SWATCHES)

    html = f'''<title>Pulse &mdash; Identity Exploration</title>
<style>{CSS}</style>

<svg width="0" height="0" style="position:absolute" aria-hidden="true">{"".join(symbols)}</svg>

<div class="wrap">
  <div class="strip">
    <span class="live">all systems nominal</span>
    <span>identity exploration</span>
    <span>{len(CONCEPTS)} directions</span>
    <span class="end">round 02</span>
  </div>

  <div class="masthead">
    <p class="eyebrow">Pulse &mdash; uptime monitoring that lives on your phone</p>
    <h1>Thirty marks for an app whose job is to <em>interrupt you</em>.</h1>
    <p class="lede">Every direction ships as two files: a square plate for the launcher, and a
    landscape lockup with the wordmark. Each is shown at four sizes, because a monitoring icon that
    only works at 192&nbsp;px is one nobody will pick out of a home screen at 3am.</p>
  </div>

  <div class="contact">
    <p class="eyebrow">All {len(CONCEPTS)} &mdash; pick one to jump to it</p>
    <ul class="grid">{"".join(tiles)}</ul>
  </div>

  <div class="system">
    <div class="syswrap">
      <div>
        <p class="eyebrow">The two-colour rule</p>
        <p>The palette comes from the app itself. <strong>Signal green</strong> is everything working.
        <strong>Alarm red</strong> appears exactly once per mark, on the single element that stands for
        failure. Nothing else gets colour.</p>
        <p>That one constraint is what keeps thirty very different shapes recognisably related, and it
        means every mark carries the product's actual idea: a steady state, and the moment it breaks.</p>
        <p>Plates are near-black rather than white so the marks sit correctly on a dark launcher and
        inside the app's own dark UI.</p>
      </div>
      <ul class="pal">{sw}</ul>
    </div>
  </div>

  {"".join(rows)}

  <div class="notes">
    <p class="eyebrow">Notes on the files</p>

    <h3>Plate for the launcher, bare mark for the lockup</h3>
    <p>The square files are full-bleed 512&nbsp;px plates &mdash; that is what an app icon is. The
    landscape files drop the plate so the mark sits at brand scale beside the wordmark, which is how a
    lockup behaves in a README header or a settings screen. Direction 04 is the exception: the plate
    <em>is</em> the mark, so it keeps its container in both. Every lockup mark is fitted from its real
    ink bounds, so all thirty sit at the same optical weight next to the type.</p>

    <h3>Wordmark type is not outlined yet</h3>
    <p>Each lockup names its intended face and falls back to a system sans, serif or mono. Before a
    chosen direction goes anywhere final, convert the wordmark to paths so it renders identically
    everywhere. Roughly two thirds want a tight geometric sans; 06, 19 and 26 want an old-style serif;
    08, 09, 12, 14, 16, 18, 22, 24 and 28 want a mono.</p>

    <h3>Android adaptive icons</h3>
    <p>For an adaptive icon the foreground layer has to survive a circular mask, so any mark that runs
    close to the plate edge &mdash; 02, 03, 08, 10, 12, 14, 15, 16, 21, 28 and 29 &mdash; needs
    roughly 15% inset before it goes into <code>ic_launcher_foreground</code>. 04 already reads
    correctly under any mask shape.</p>

    <h3>Regenerating</h3>
    <p>The thirty icon SVGs are the only hand-authored files. Lockups, PNG exports and this page all
    derive from them.</p>
    <pre>python3 docs/brand/build.py --png</pre>

    <p>A light-ground variant is a two-value swap: plate <code>#0B0E13</code> &rarr;
    <code>#F2F5F8</code>, glyph <code>#F2F5F8</code> &rarr; <code>#0B0E13</code>. Green and red are
    legible on both grounds and stay exactly as they are.</p>
  </div>
</div>
'''
    open(os.path.join(HERE, "logos.html"), "w").write(html)


if __name__ == "__main__":
    bbox = measure_bboxes()
    build_lockups(bbox)
    if "--png" in sys.argv:
        build_png()
    build_html()
    print(f"wrote {len(CONCEPTS)} lockups + logos.html" + (" + png/" if "--png" in sys.argv else ""))
