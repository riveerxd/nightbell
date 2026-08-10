#!/usr/bin/env bash
# The 1024x500 feature graphic, for F-Droid and IzzyOnDroid listings.
#
# Usage: docs/brand/feature_graphic.sh <raw_capture_dir> [out.png]
#
# <raw_capture_dir> holds raw phone captures. It needs two of them:
#   urgent-5-brief.png   UrgentPageDesignTest#brief
#   10-dashboard.png     ScreenshotTest#capturesTheWholeProduct
#
# Both must come from a build of the current release, and the emulator must be in
# dark mode when they are taken. UrgentPageDesignTest builds its own theme rather
# than reading a seeded setting, so it follows the system: on a light-mode
# emulator the urgent page comes out with a pale lower half and stat tiles whose
# values are invisible, and it is not obvious from the file that anything is
# wrong. `adb shell cmd uimode night yes` first.
#
# ## What the composition is allowed to do
#
# DESIGN_NOTES.md's refused list governs this the same as the page, and two of
# its rules shape everything here:
#
# "No screenshot floating in a glass pane, and no screenshot arriving with a
# background of its own." So the rasters are product UI and nothing else, and the
# phone around them is drawn by docs/mockup.sh, which draws its frame rather than
# pasting downloaded device art.
#
# The graticule is allowed in the hero on one condition, that it is masked out
# before it reaches any text. Here it starts at x=470 and ramps in over 170px, so
# full strength sits under the phones and nothing at all sits under the headline.
#
# The vignette is the same radial the README banners use. It is offset right so
# its bright point falls under the hardware: centred, it would sit behind the
# headline, and a wash bright enough to notice behind type is the one thing the
# notes name outright.
set -euo pipefail

RAW=${1:?usage: feature_graphic.sh <raw_capture_dir> [out.png]}
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../.." && pwd)
OUT=${2:-$ROOT/fastlane/metadata/android/en-US/images/featureGraphic.png}
mkdir -p "$(dirname "$OUT")"

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT

W=1024; H=500
GLOW='radial-gradient:#17264a-#070910'
G0=470; RAMP=170
DIM='#121212'   # this value is the final grid-line opacity, not a colour

# Inter, instanced out of the variable woff2 the site ships. ImageMagick cannot
# read woff2, and the wordmark PNG is reused rather than re-rendered from
# logo-dark.svg because that SVG names DejaVu Sans, which is not installed here;
# re-rendering it would silently pick a different fallback than the README's.
FONTS="$TMP/fonts"; mkdir -p "$FONTS"
python3 - "$ROOT/website/public/fonts/inter-latin-wght-normal.woff2" "$FONTS" <<'PY'
import sys
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
src, out = sys.argv[1], sys.argv[2]
for wght, name in ((600, 'SemiBold'), (400, 'Regular')):
    f = TTFont(src)
    if 'fvar' in f:
        instantiateVariableFont(f, {'wght': wght}, inplace=True)
    f.flavor = None
    f.save(f"{out}/Inter-{name}.ttf")
PY

# -- phones -------------------------------------------------------------------
bash "$ROOT/docs/mockup.sh" "$RAW/urgent-5-brief.png" "$TMP/pf.png" 0.235 >/dev/null
bash "$ROOT/docs/mockup.sh" "$RAW/10-dashboard.png"   "$TMP/pb.png" 0.20  >/dev/null

# -- backdrop -----------------------------------------------------------------
magick -size 1500x1500 "$GLOW" -resize 1500x1500^ \
  -gravity center -crop ${W}x${H}+215+0 +repage "$TMP/bg.png"

GRID=""
for x in $(seq $G0 68 $W); do GRID="$GRID line $x,0,$x,$H"; done
for y in $(seq 0 68 $H);   do GRID="$GRID line $G0,$y,$W,$y"; done

# Lines full strength, mask does the dimming. The other order does not work:
# -alpha off before CopyOpacity discards the stroke's own opacity, and a 6% line
# comes back solid white.
magick -size ${W}x${H} xc:none -fill none -stroke white -strokewidth 1 \
  -draw "$GRID" "$TMP/grid-raw.png"
magick -size ${H}x${RAMP} gradient:black-"$DIM" -rotate 90 "$TMP/ramp.png"
magick -size ${W}x${H} xc:black \
  "$TMP/ramp.png" -geometry +${G0}+0 -composite \
  \( -size $((W - G0 - RAMP))x${H} xc:"$DIM" \) -geometry +$((G0 + RAMP))+0 -composite \
  "$TMP/fade.png"
magick "$TMP/grid-raw.png" "$TMP/fade.png" -alpha off -compose CopyOpacity -composite "$TMP/grid.png"
magick "$TMP/bg.png" "$TMP/grid.png" -composite "$TMP/base.png"

# -- type ---------------------------------------------------------------------
magick "$TMP/base.png" \
  \( "$ROOT/docs/screens/logo-dark.png" -resize 268x \) -geometry +58+122 -composite \
  -font "$FONTS/Inter-SemiBold.ttf" -pointsize 33 -fill "#FFFFFF" \
  -annotate +60+266 "Uptime monitoring that" \
  -annotate +60+308 "runs on your phone." \
  -font "$FONTS/Inter-Regular.ttf" -pointsize 18 -fill "#8b96a8" \
  -annotate +61+356 "No server. No account. It wakes you up." \
  "$TMP/typed.png"

# -- phones over the type, back one first -------------------------------------
magick "$TMP/typed.png" \
  "$TMP/pb.png" -geometry +492+30 -composite \
  "$TMP/pf.png" -geometry +668+66 -composite \
  -strip "$OUT"

magick identify -format "wrote %f  %wx%h  %b\n" "$OUT"
