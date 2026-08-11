#!/usr/bin/env bash
# Check a release APK against everything F-Droid will check, before publishing it.
#
#   docs/fdroid-preflight.sh [apk]
#
# Defaults to app/build/outputs/apk/release/app-release.apk.
#
# The point is that all four failures from the first submission are visible here in
# under a second, and each of them otherwise costs a nine-minute pipeline round trip
# or, worse, a published release that has to be replaced. See docs/FDROID.md.
#
# Exit status is the number of failed checks, so it is usable in a chain.
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd); ROOT=$(cd "$HERE/.." && pwd)
APK=${1:-$ROOT/app/build/outputs/apk/release/app-release.apk}

# The JDK F-Droid's buildserver installs. Read it off a recent `fdroid build` job
# log if this ever moves: the container apt-installs it in the clear at the top.
WANT_JDK=21.0.12

FAIL=0
ok()   { printf '  \033[32mok\033[0m    %s\n' "$1"; }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAIL=$((FAIL+1)); }
note() { printf '        %s\n' "$1"; }

[ -f "$APK" ] || { echo "no APK at $APK"; exit 1; }
echo "checking $(basename "$APK")"
echo

# -- 1, the JDK ---------------------------------------------------------------
#
# A different JDK gives R8 a different classes.dex, and drags baseline.prof along
# with it because the profile is compiled into dex method indices. This checks the
# JDK on PATH now, which is only the same thing as the JDK that built the APK if
# nothing was switched in between. Build and check in one sitting.
JDK=$(java -version 2>&1 | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
if [ "$JDK" = "$WANT_JDK" ]; then
  ok "JDK $JDK"
else
  bad "JDK is $JDK, F-Droid's buildserver runs $WANT_JDK"
  note "sudo archlinux-java set java-21-openjdk"
fi

VER=$(grep -oE 'versionName = "[^"]+"' "$ROOT/app/build.gradle.kts" | tail -1 | cut -d'"' -f2)
CODE=$(grep -oE 'versionCode = [0-9]+' "$ROOT/app/build.gradle.kts" | tail -1 | grep -oE '[0-9]+')

# -- 2, the recorded revision -------------------------------------------------
#
# AGP writes the git revision at build time into the APK, and F-Droid builds the
# tag, so the two have to agree. What that means depends on when this runs.
#
# Before tagging, which is the normal preflight moment, the APK should record HEAD:
# the version bump is committed and this is the commit about to be tagged.
#
# After tagging it should record whatever v<version> points at, and HEAD is allowed
# to have moved on, because the release procedure commits the artifacts copy of the
# APK after the tag. Comparing against HEAD in that state reports a failure for a
# repository that is in exactly the right shape, which it did until this was fixed.
REV=$(unzip -p "$APK" META-INF/version-control-info.textproto 2>/dev/null |
      grep -oE '[0-9a-f]{40}' | head -1)
TAGREF=$(git -C "$ROOT" rev-parse -q --verify "v$VER^{commit}" 2>/dev/null)
HEAD=$(git -C "$ROOT" rev-parse HEAD 2>/dev/null)

if [ -z "$REV" ]; then
  ok "no version-control-info in the APK, nothing to match"
elif [ -n "$TAGREF" ]; then
  if [ "$REV" = "$TAGREF" ]; then
    ok "recorded revision matches tag v$VER  ${REV:0:12}"
  else
    bad "recorded revision ${REV:0:12} is not tag v$VER ${TAGREF:0:12}"
    note "either the APK predates the tagged commit, or the tag is on the wrong one"
  fi
elif [ "$REV" = "$HEAD" ]; then
  ok "recorded revision matches HEAD  ${REV:0:12}"
  note "not tagged yet; tag this commit, not a later one"
else
  bad "recorded revision ${REV:0:12} is not HEAD ${HEAD:0:12}"
  note "the APK was built before the release commit; rebuild after committing"
fi

# -- 3, the signing block -----------------------------------------------------
#
# Anything beyond the signature and its padding fails `check apk`. In practice that
# means AGP's Play dependency-metadata blob, which dependenciesInfo turns off.
BLOCKS=$(python3 - "$APK" <<'PY'
import struct, sys
KNOWN = {0x7109871a:'v2 signature', 0xf05368c0:'v3 signature', 0x1b93ad61:'v4 signature',
         0x42726577:'padding', 0x504b4453:'Play dependency metadata', 0x2146444e:'source stamp'}
ALLOWED = {0x7109871a, 0xf05368c0, 0x1b93ad61, 0x42726577}
d = open(sys.argv[1], 'rb').read()
i = d.rfind(b'APK Sig Block 42')
if i < 0:
    print('NOBLOCK'); sys.exit()
size_end = struct.unpack('<Q', d[i-8:i])[0]
p = i + 16 - size_end - 8 + 8
while p < i - 8:
    ln  = struct.unpack('<Q', d[p:p+8])[0]
    bid = struct.unpack('<I', d[p+8:p+12])[0]
    print('%s|0x%08x|%s|%d' % ('OK' if bid in ALLOWED else 'EXTRA',
                               bid, KNOWN.get(bid, 'unknown'), ln))
    p += 8 + ln
PY
)
if [ "$BLOCKS" = "NOBLOCK" ]; then
  bad "no APK signing block at all, is this APK signed?"
else
  EXTRA=$(echo "$BLOCKS" | grep -c '^EXTRA' || true)
  if [ "$EXTRA" = "0" ]; then
    ok "signing block clean"
  else
    bad "$EXTRA extra signing block(s), check apk will reject this"
    note "dependenciesInfo { includeInApk = false; includeInBundle = false }"
  fi
  echo "$BLOCKS" | while IFS='|' read -r st id name len; do
    note "$(printf '%-6s %s  %s bytes' "$id" "$name" "$len")"
  done
fi

# -- 4, the signing certificate -----------------------------------------------
#
# Has to match AllowedAPKSigningKeys in the fdroiddata metadata, or verification is
# rejected before the file comparison even happens.
WANT_CERT=20d8abdaa8416a9a751e3ea144ef1523d7ddbaaeee9ec6be01d63a65574a70de
APKSIGNER=$(ls "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)
if [ -z "$APKSIGNER" ]; then
  note "apksigner not found, skipping the certificate check"
else
  CERT=$("$APKSIGNER" verify --print-certs "$APK" 2>/dev/null |
         grep -i 'certificate SHA-256 digest' | grep -oE '[0-9a-f]{64}' | head -1)
  if [ "$CERT" = "$WANT_CERT" ]; then
    ok "signing certificate matches AllowedAPKSigningKeys"
  else
    bad "signing certificate is ${CERT:0:16}, metadata pins ${WANT_CERT:0:16}"
  fi
fi

# -- 5, the release asset name ------------------------------------------------
#
# The Binaries pattern is .../download/v%v/Nightbell-%v-release.apk, so both the
# tag and the asset filename are load bearing.
note ""
note "version $VER (code $CODE)"
note "tag must be      v$VER"
note "asset must be    Nightbell-$VER-release.apk"
note "changelog        fastlane/metadata/android/en-US/changelogs/$CODE.txt"
[ -f "$ROOT/fastlane/metadata/android/en-US/changelogs/$CODE.txt" ] \
  && ok "changelog for $CODE exists" \
  || bad "no changelog at fastlane/metadata/android/en-US/changelogs/$CODE.txt"

echo
[ "$FAIL" = "0" ] && echo "all checks passed" || echo "$FAIL check(s) failed"
exit "$FAIL"
