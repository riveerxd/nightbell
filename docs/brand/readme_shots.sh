#!/usr/bin/env bash
# Rebuilds the README's branded banners from raw 1080x2340 captures.
#
# Raw captures (dark theme, latest build) come from:
#   ScreenshotTest#capturesTheWholeProduct -> 10-dashboard, 11-detail-failing,
#       13/14/15-settings-*, 16..19-setup-*
#   a first-launch grab with hasSeenPagerSetup=false -> setup.png
#   the urgent CALL_CUSTOM heads-up, captured over a branded home -> urgent-notification.png
#   WidgetSizeScreenshotTest -> widget-4x4, widget-two-columns, widget-wide-flat
#
# The urgent shot is the real heads-up over a de-cluttered home: a dark branded
# wallpaper set via WallpaperManager, the stock Google apps uninstalled, so the
# background is Pulse's own surface rather than the emulator's launcher.
#
# Framing is docs/mockup.sh (hand-drawn, no downloaded device art). The backdrop
# is a blue-glow radial so the phones sit on a designed surface, not white.
#
#   docs/brand/readme_shots.sh <RAW_DIR>
set -euo pipefail
RAW=${1:?usage: readme_shots.sh <raw_capture_dir>}
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../.." && pwd)
OUT="$ROOT/docs/screens"; mkdir -p "$OUT"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
GLOW='radial-gradient:#17264a-#070910'
frame(){ bash "$ROOT/docs/mockup.sh" "$RAW/$1" "$TMP/$2" "${3:-0.46}" >/dev/null; }
band(){ local w h; w=$(magick identify -format '%w' "$2"); h=$(magick identify -format '%h' "$2")
  magick -size $((w+160))x$((h+140)) "$GLOW" "$TMP/bg.png"
  magick "$TMP/bg.png" "$2" -gravity center -composite "$1"; }

# theme-aware logos from source SVG
magick -density 220 -background none "$HERE/logo-dark.svg"  -resize 760x "$OUT/logo-dark.png"
magick -density 220 -background none "$HERE/logo-light.svg" -resize 760x "$OUT/logo-light.png"

# hero: logo + dashboard · urgent notification · detail
frame 10-dashboard.png d.png; frame urgent-notification.png u.png; frame 11-detail-failing.png t.png
magick montage "$TMP/d.png" "$TMP/u.png" "$TMP/t.png" -tile 3x1 -geometry +34+0 -background none "$TMP/hrow.png"
magick -size 1960x1680 "$GLOW" "$TMP/hbg.png"
magick "$TMP/hbg.png" \( "$OUT/logo-dark.png" -resize 1040x \) -gravity North -geometry +0+70 -composite \
  "$TMP/hrow.png" -gravity South -geometry +0+40 -composite "$OUT/hero-b.png"

# single framed phones for the Why and Alerts sections
cp "$TMP/u.png" "$OUT/urgent-b.png"
frame setup.png s.png; cp "$TMP/s.png" "$OUT/setup-b.png"

# create-a-monitor wizard
for i in 16-setup-kind 17-setup-target 18-setup-expectations 19-setup-cadence-alerts; do frame "$i.png" "$i.png" 0.40; done
magick montage "$TMP"/1{6,7,8,9}-*.png -tile 4x1 -geometry +30+0 -background none "$TMP/wiz.png"; band "$OUT/create-monitor.png" "$TMP/wiz.png"

# settings
for i in 13-settings-top 14-settings-haptics 15-settings-escalation; do frame "$i.png" "$i.png"; done
magick montage "$TMP"/1{3,4,5}-*.png -tile 3x1 -geometry +30+0 -background none "$TMP/set.png"; band "$OUT/settings-group.png" "$TMP/set.png"

# widget column-flow strip (raw widget bitmaps)
magick montage "$RAW/widget-4x4.png" "$RAW/widget-two-columns.png" "$RAW/widget-wide-flat.png" \
  -tile 3x1 -geometry +46+0 -background none "$TMP/wrow.png"; band "$OUT/widget-243.png" "$TMP/wrow.png"
echo "wrote docs/screens/{logo-dark,logo-light,hero-b,urgent-b,setup-b,create-monitor,settings-group,widget-243}.png"
