#!/bin/sh
# Tries the app on a running emulator or a phone with adb: installs it, opens its screen, makes the snow the
# wallpaper as a person would, turns Deep Interaction on, and keeps a screenshot of each step along with what the
# app logged. Usage: smoke.sh <folder with the APK> <folder for what it saw>. Fails if the app crashed, its snow
# couldn't be drawn, or the device stopped answering.
set -u
apk=$(ls "$1"/*.apk | head -n 1)
out=$2
app=app.snowhush.android
mkdir -p "$out"

# With no one watching, what the script did and what adb complained of is all there is to go by.
exec 2> "$out/trace.txt"
set -x

# An emulator that has fallen over answers nothing: no call waits on it for ever.
adb() {
    timeout 120 adb "$@"
}

# Everything the device logs, as it comes: what it said last is kept even if it falls over.
command adb logcat -v threadtime > "$out/logcat-all.txt" &
logcat=$!

finish() {
    kill "$logcat" 2> /dev/null
    grep -E " (Snowhush|AndroidRuntime) *:" "$out/logcat-all.txt" > "$out/logcat.txt"
    exit "$1"
}

# Stops as soon as the device no longer answers, and says after what.
alive() {
    [ "$(adb get-state 2> /dev/null)" = device ] && return 0
    echo "the device stopped answering $1" | tee -a "$out/verdict.txt"
    adb devices -l >> "$out/verdict.txt" 2>&1
    finish 1
}

shot() {
    adb exec-out screencap -p > "$out/$1.png"
    [ -s "$out/$1.png" ] || echo "no picture of $1" | tee -a "$out/verdict.txt"
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

alive "before anything"
{
    adb devices -l
    adb shell getprop ro.build.fingerprint
    adb shell getprop ro.hardware.egl
    adb shell wm size
    adb shell wm density
    adb shell cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN
} > "$out/device.txt"
adb install -r "$apk" || finish 1
alive "once the app was installed"
shot 0-before

# The app's own screen, with snow on its cards.
adb shell am start -W -n "$app/.MainActivity"
sleep 5
alive "once the app's screen opened"
sleep 25
shot 1-app
adb shell input swipe 300 900 300 400 300
sleep 4
shot 2-app-scrolled
alive "with the app's screen open"

# Android's own preview of the wallpaper, then the wallpaper set for the home and lock screens.
adb shell am start -W -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER \
    --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT "$app/.SnowWallpaper"
sleep 25
alive "previewing the wallpaper"
shot 3-wallpaper-preview
if tap "^ *set wallpaper"; then
    sleep 3
    tap "home screen and lock screen|home and lock" || tap "home screen" || true
    sleep 3
fi
adb pull /sdcard/ui.xml "$out/ui-preview.xml" > /dev/null 2>&1
adb shell input keyevent KEYCODE_HOME
sleep 30
alive "with the wallpaper on the home screen"
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
sleep 45
alive "with Deep Interaction on"
shot 6-home-deep
adb shell uiautomator dump /sdcard/home.xml > /dev/null 2>&1 && adb pull /sdcard/home.xml "$out/ui-home.xml" > /dev/null 2>&1
adb shell input keyevent KEYCODE_POWER
sleep 3
adb shell input keyevent KEYCODE_WAKEUP
sleep 8
shot 7-lock
alive "at the end"

sleep 2
kill "$logcat" 2> /dev/null
grep -E " (Snowhush|AndroidRuntime) *:" "$out/logcat-all.txt" > "$out/logcat.txt"

status=0
if grep -q "no picture of" "$out/verdict.txt" 2> /dev/null; then
    status=1
fi
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
    # Where the snow was told to lie, to hold against the screenshot.
    grep "snow lies at" "$out/logcat.txt" | tail -n 12 | sed 's/.*Snowhush: //' | tee -a "$out/verdict.txt"
else
    echo "Deep Interaction read no home screen" | tee -a "$out/verdict.txt"
fi
[ "$status" -eq 0 ] && echo "the app runs" | tee -a "$out/verdict.txt"
exit "$status"
