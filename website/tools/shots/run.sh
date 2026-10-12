#!/usr/bin/env bash
# Rebuilds the website screenshots at 1440x3200 without touching app/.
#
# Copies the app into a scratch tree, drops src/devMark and src/debug/res so the
# bell is release blue and the label reads Nightbell, adds the capture test, and
# installs onto the Pixel_6_API_34 AVD with its display overridden to 560 dpi.
# nb_agent is no good for this: its display cutout is 128px at 210 dpi, which
# pushes every screen down by a 97dp status bar.
#
# Boot first, then unlock (the AVD's PIN is 1234):
#   ~/Android/Sdk/emulator/emulator -avd Pixel_6_API_34 -no-window -no-audio \
#     -no-boot-anim -gpu swiftshader_indirect -no-snapshot -memory 4096 \
#     -dns-server 8.8.8.8,1.1.1.1
#
# Each test method is run on its own. The urgent ones need the screen unlocked
# (urgentInShade, urgentOverHomeScreen) or about to be locked by the test
# (urgentWakesLockscreen); unlock again between them. Run with
# `appops set me.river.nightbell.debug USE_FULL_SCREEN_INTENT deny` for the
# lockscreen one, or the full-screen page takes the screen instead, and set it
# back to default before pagerSetup or that screen reads 1 of 4.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
WORK="${WORK:-$(mktemp -d)}"
HERE="$(cd "$(dirname "$0")" && pwd)"

rsync -a --exclude build --exclude .gradle "$REPO/build.gradle.kts" "$REPO/settings.gradle.kts" \
  "$REPO/gradle.properties" "$REPO/gradlew" "$REPO/local.properties" "$REPO/gradle" "$REPO/app" "$WORK/"
mkdir -p "$WORK/website/public/v1" && cp "$REPO/website/public/v1/release.json" "$WORK/website/public/v1/"
rm -f "$WORK/app/src/devMark/res/values/colors.xml" "$WORK/app/src/debug/res/values/strings.xml"
cp "$HERE/WebsiteShotsInstrumentedTest.kt" "$WORK/app/src/androidTest/kotlin/me/river/nightbell/"
(cd "$WORK" && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -q)

adb install -r -t "$WORK/app/build/outputs/apk/debug/app-debug.apk"
adb install -r -t "$WORK/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
adb shell pm grant me.river.nightbell.debug android.permission.POST_NOTIFICATIONS
adb shell wm size 1440x3200
adb shell wm density 560
adb shell settings put global sysui_demo_allowed 1
d() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
d enter; d clock -e hhmm 0314; d battery -e level 100 -e plugged false
# Without these two the mobile bars stay hidden on this image even when
# mobile show is sent, and the status bar no longer matches older captures.
d network -e airplane hide; d network -e nosim hide
d network -e wifi show -e level 4 -e fully true
d network -e mobile show -e datatype none -e level 4 -e fully true -e slot 0
d notifications -e visible false

run() {
  adb shell am instrument -w -e class "me.river.nightbell.$1" \
    me.river.nightbell.debug.test/androidx.test.runner.AndroidJUnitRunner
}
for m in calmDashboard calmDashboardLight downDashboardAndDetail elementDetail wizard pagerSetup widgets alarmActivity; do
  run "WebsiteShotsInstrumentedTest#$m"
done
run WebsiteAlarmShotInstrumentedTest

OUT="${OUT:-$HERE/out}"
mkdir -p "$OUT"
for f in $(adb exec-out run-as me.river.nightbell.debug ls files/screenshots/ | tr -d '\r'); do
  adb exec-out run-as me.river.nightbell.debug cat "files/screenshots/$f" > "$OUT/$f"
done
echo "Captures in $OUT. Reset with: wm size reset, wm density reset, demo exit."
