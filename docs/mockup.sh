#!/usr/bin/env bash
# Wraps a raw 1080x2340 screenshot in a phone frame for the README.
#
# The frame is drawn here rather than pasted from a downloaded device mockup, so
# there is nothing in this repo whose licence I cannot account for.
#
# Usage: docs/mockup.sh <screenshot.png> <out.png> [scale]
#   scale defaults to 0.42, which puts three phones comfortably side by side.
set -euo pipefail

SRC=${1:?usage: mockup.sh <in.png> <out.png> [scale]}
OUT=${2:?usage: mockup.sh <in.png> <out.png> [scale]}
SCALE=${3:-0.42}

W=1080
H=2340
RADIUS=76      # screen corner radius
BEZEL=26       # black surround
RIM=4          # metal edge highlight
CAM_R=17       # punch-hole camera
CAM_Y=42       # from the top of the screen

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# The screenshot, scaled to the exact panel size and with rounded corners.
magick "$SRC" -resize "${W}x${H}!" "$TMP/panel.png"
magick -size "${W}x${H}" xc:black -fill white \
  -draw "roundrectangle 0,0,$((W-1)),$((H-1)),$RADIUS,$RADIUS" "$TMP/mask.png"
magick "$TMP/panel.png" "$TMP/mask.png" -alpha Off -compose CopyOpacity -composite "$TMP/screen.png"

# Punch-hole camera, with a faint ring so it reads as glass and not a dead pixel.
magick "$TMP/screen.png" \
  -fill "#05050a" -stroke "#1b1d27" -strokewidth 2 \
  -draw "circle $((W/2)),$CAM_Y $((W/2 + CAM_R)),$CAM_Y" \
  "$TMP/screen-cam.png"

# Body: black surround with a lighter rim, the way an aluminium edge catches light.
OW=$((W + 2*BEZEL))
OH=$((H + 2*BEZEL))
BR=$((RADIUS + BEZEL))
magick -size "${OW}x${OH}" xc:none \
  -fill "#0a0a0c" -stroke "#3a3d47" -strokewidth "$RIM" \
  -draw "roundrectangle $((RIM/2)),$((RIM/2)),$((OW-1-RIM/2)),$((OH-1-RIM/2)),$BR,$BR" \
  "$TMP/body.png"

# Side buttons on the right edge: volume rocker and power.
magick "$TMP/body.png" -fill "#2b2e36" -stroke none \
  -draw "roundrectangle $((OW-7)),520,$((OW-1)),760,3,3" \
  -draw "roundrectangle $((OW-7)),830,$((OW-1)),980,3,3" \
  "$TMP/body-btn.png"

magick "$TMP/body-btn.png" "$TMP/screen-cam.png" -geometry "+${BEZEL}+${BEZEL}" -composite "$TMP/framed.png"

# Scale, then a soft shadow so it sits on the page instead of floating flat.
magick "$TMP/framed.png" -resize "$(awk "BEGIN{printf \"%d\", $OW * $SCALE}")x" "$TMP/small.png"
magick "$TMP/small.png" \
  \( +clone -background "#000000" -shadow 50x18+0+10 \) \
  +swap -background none -layers merge +repage "$OUT"

echo "$OUT  $(identify -format '%wx%h' "$OUT")"
