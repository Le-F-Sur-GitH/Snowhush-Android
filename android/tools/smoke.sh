#!/bin/sh
# Tries the app on a running emulator or a phone with adb: installs it, opens its screen, makes the snow the
# wallpaper as a person would, turns Deep Interaction on, and keeps a screenshot of each step along with what the
# app logged. Usage: smoke.sh <folder with the APK> <folder for what it saw>. Fails if the app crashed or its snow
# couldn't be drawn.
set -u
apk=$(ls "$1"/*.apk | head -n 1)
out=$2
app=app.snowhush.android
mkdir -p "$out"

# An emulator that has fallen over answers nothing: no call waits on it for ever.
adb() {
    timeout 120 adb "$@"
}

adb install -r "$apk" || exit 1
adb logcat -c

shot() {
    adb exec-out screencap -p > "$out/$1.png"
}

# Taps the first thing on the screen whose words match, if there is one.
tap() {
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || return 1
    adb pull /sdcard/ui.xml "$out/ui.xml" > /dev/null 2>&1 || return 1
    at=$(python3 - "$out/ui.xml" "$1" << 'PY'
import re, sys, xml.etree.ElementTree as ET
wanted = re.compile(sys.argv[2], re.I)
for node in ET.parse(sys.argv[1]).iter("node"):
    words = (node.get("text") or "") + " " + (node.get("content-desc") or "")
    if wanted.search(words):
        left, top, right, bottom = map(int, re.findall(r"-?\d+", node.get("bounds")))
        print((left + right) // 2, (top + bottom) // 2)
        break
PY
    )
    [ -n "$at" ] || return 1
    # shellcheck disable=SC2086
    adb shell input tap $at
}

# The app's own screen, with snow on its cards.
adb shell am start -W -n "$app/.MainActivity"
sleep 30
shot 1-app
adb shell input swipe 500 1600 500 900 300
sleep 4
shot 2-app-scrolled

# Android's own preview of the wallpaper, then the wallpaper set for the home and lock screens.
adb shell am start -W -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER \
    --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT "$app/.SnowWallpaper"
sleep 25
shot 3-wallpaper-preview
if tap "^ *set wallpaper"; then
    sleep 3
    tap "home screen and lock screen|home and lock" || tap "home screen" || true
    sleep 3
fi
adb shell input keyevent KEYCODE_HOME
sleep 30
shot 4-home
adb shell dumpsys wallpaper > "$out/wallpaper.txt"

# Deep Interaction: the snow is told where the home screen's widgets and icons are.
adb shell settings put secure enabled_accessibility_services "$app/$app.DeepService"
adb shell settings put secure accessibility_enabled 1
sleep 8
adb shell am start -W -n "$app/.MainActivity"
sleep 4
shot 5-app-deep
adb shell input keyevent KEYCODE_HOME
sleep 75
shot 6-home-deep
adb shell input keyevent KEYCODE_POWER
sleep 3
adb shell input keyevent KEYCODE_WAKEUP
sleep 8
shot 7-lock

adb logcat -d > "$out/logcat-all.txt"
adb logcat -d -s Snowhush:V AndroidRuntime:E > "$out/logcat.txt"

status=0
if grep -q "Process: $app" "$out/logcat-all.txt"; then
    echo "the app crashed" | tee -a "$out/verdict.txt"
    status=1
fi
if grep -q "the snow can't be drawn\|the snow stopped" "$out/logcat.txt"; then
    echo "the snow couldn't be drawn" | tee -a "$out/verdict.txt"
    status=1
fi
if grep -q "$app" "$out/wallpaper.txt"; then
    echo "the snow is the wallpaper" | tee -a "$out/verdict.txt"
else
    echo "the wallpaper couldn't be set by tapping; only its preview was seen" | tee -a "$out/verdict.txt"
fi
if grep -q "home screen:" "$out/logcat.txt"; then
    grep "home screen:" "$out/logcat.txt" | tail -n 1 | tee -a "$out/verdict.txt"
else
    echo "Deep Interaction read no home screen" | tee -a "$out/verdict.txt"
fi
[ "$status" -eq 0 ] && echo "the app runs" | tee -a "$out/verdict.txt"
exit "$status"
