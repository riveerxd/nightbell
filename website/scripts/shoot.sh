#!/usr/bin/env bash
# Inspection screenshots, at the two sizes that actually decide the layout.
#
# Run `npm run preview` in another shell first, then:
#
#   ./scripts/shoot.sh                       # http://localhost:4321
#   ./scripts/shoot.sh http://localhost:4321
#
# Writes into screenshots/. Sections are captured by scrolling to their anchor
# rather than by pixel offset, so the set stays meaningful when the page grows.
set -euo pipefail

URL="${1:-http://localhost:4321}"
OUT="$(cd "$(dirname "$0")/.." && pwd)/screenshots"
SESSION=nightbell-shoot
mkdir -p "$OUT"

shoot() { # width height prefix
  local w=$1 h=$2 prefix=$3
  agent-browser --session "$SESSION" set viewport "$w" "$h" >/dev/null
  agent-browser --session "$SESSION" open "$URL" >/dev/null
  agent-browser --session "$SESSION" wait --load networkidle >/dev/null
  agent-browser --session "$SESSION" screenshot "$OUT/$prefix-01-hero.png" >/dev/null

  local i=2
  for anchor in why on-device watch setup urgent limits install faq; do
    # Loading is lazy, so give the images a beat after the jump before capturing.
    agent-browser --session "$SESSION" eval \
      "document.getElementById('$anchor').scrollIntoView(); 'ok'" >/dev/null
    sleep 1.2
    printf -v n '%02d' "$i"
    agent-browser --session "$SESSION" screenshot "$OUT/$prefix-$n-$anchor.png" >/dev/null
    i=$((i + 1))
  done

  agent-browser --session "$SESSION" eval \
    "window.scrollTo(0, document.body.scrollHeight); 'ok'" >/dev/null
  sleep 0.6
  agent-browser --session "$SESSION" screenshot "$OUT/$prefix-10-footer.png" >/dev/null
}

shoot 1440 900 desktop
shoot 390 844 mobile

agent-browser --session "$SESSION" close >/dev/null 2>&1 || true
echo "Screenshots in $OUT"
