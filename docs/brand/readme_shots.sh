#!/usr/bin/env bash
# Rebuilds the README's branded banners from raw 1080x2340 captures.
#
# The captures come from the on-device harnesses (dark theme, latest build):
#   ScreenshotTest#capturesTheWholeProduct  -> 10-dashboard, 11-detail-failing,
#       13/14/15-settings-*, 16..19-setup-*
#   Zz urgent full-screen grab              -> urgent-fullscreen.png
#   WidgetSizeScreenshotTest                -> widget-4x4, widget-two-columns, widget-wide-flat
# Pull them into $RAW, then run:  docs/brand/readme_shots.sh <RAW_DIR>
#
# Framing is docs/mockup.sh (hand-drawn, no downloaded device art). The backdrop
# is a blue-glow radial so the phones sit on a designed surface, not white.
set -euo pipefail
RAW=${1:?usage: readme_shots.sh <raw_capture_dir>}
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT="$ROOT/docs/screens"; mkdir -p "$OUT"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
GLOW='radial-gradient:#17264a-#070910'

frame() { bash "$ROOT/docs/mockup.sh" "$RAW/$1" "$TMP/$2" "$3" >/dev/null; }
band() { # out row.png pad_w pad_h
  local w h; w=$(magick identify -format '%w' "$2"); h=$(magick identify -format '%h' "$2")
  magick -size $((w+$3))x$((h+$4)) "$GLOW" "$TMP/bg.png"
  magick "$TMP/bg.png" "$2" -gravity center -composite "$1"
}

# logos (theme-aware) from source SVG
magick -density 220 -background none "$HERE/logo-dark.svg"  -resize 760x "$OUT/logo-dark.png"
magick -density 220 -background none "$HERE/logo-light.svg" -resize 760x "$OUT/logo-light.png"

# hero: logo + dashboard · urgent · detail
frame 10-dashboard.png d.png 0.46; frame urgent-fullscreen.png u.png 0.46; frame 11-detail-failing.png t.png 0.46
magick montage "$TMP/d.png" "$TMP/u.png" "$TMP/t.png" -tile 3x1 -geometry +34+0 -background none "$TMP/hrow.png"
magick -size 1960x1680 "$GLOW" "$TMP/hbg.png"
magick "$TMP/hbg.png" \( "$OUT/logo-dark.png" -resize 1040x \) -gravity North -geometry +0+70 -composite \
  "$TMP/hrow.png" -gravity South -geometry +0+40 -composite "$OUT/hero-243.png"

# create-a-monitor wizard
for i in 16-setup-kind 17-setup-target 18-setup-expectations 19-setup-cadence-alerts; do frame "$i.png" "$i.png" 0.40; done
magick montage "$TMP"/1{6,7,8,9}-*.png -tile 4x1 -geometry +30+0 -background none "$TMP/srow.png"
band "$OUT/create-monitor.png" "$TMP/srow.png" 160 140

# settings
for i in 13-settings-top 14-settings-haptics 15-settings-escalation; do frame "$i.png" "$i.png" 0.46; done
magick montage "$TMP"/1{3,4,5}-*.png -tile 3x1 -geometry +30+0 -background none "$TMP/grow.png"
band "$OUT/settings-group.png" "$TMP/grow.png" 160 140

# widget column-flow strip (raw widget bitmaps, not phone-framed)
magick montage "$RAW/widget-4x4.png" "$RAW/widget-two-columns.png" "$RAW/widget-wide-flat.png" \
  -tile 3x1 -geometry +46+0 -background none "$TMP/wrow.png"
band "$OUT/widget-243.png" "$TMP/wrow.png" 160 140

# single framed urgent for the Why section
frame urgent-fullscreen.png urg.png 0.46; cp "$TMP/urg.png" "$OUT/urgent.png"
echo "wrote docs/screens/{logo-dark,logo-light,hero,create-monitor,settings-group,widget-sizes,urgent}.png"
