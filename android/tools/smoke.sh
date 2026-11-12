#!/bin/sh
# Tries the app on a running emulator or a phone with adb: installs it, opens its screen, turns the snow on over
# every app and then with Deep Interaction, and keeps a screenshot of each along with what the app logged.
# Usage: smoke.sh <folder with the APK> <folder for what it saw>. Fails if the app crashed or its window never came.
set -u
apk=$(ls "$1"/*.apk | head -n 1)
out=$2
app=app.snowhush.android
mkdir -p "$out"

adb install -r "$apk" || exit 1
adb logcat -c
# What a person allows by hand: display over other apps, and the notification that keeps the snow up.
adb shell appops set "$app" SYSTEM_ALERT_WINDOW allow
adb shell pm grant "$app" android.permission.POST_NOTIFICATIONS || true

shot() {
    adb exec-out screencap -p > "$out/$1.png"
}

# The app's own screen, with snow of its own on its cards.
adb shell am start -W -n "$app/.MainActivity"
sleep 30
shot 1-app
adb shell input swipe 500 1600 500 900 300
sleep 4
shot 2-app-scrolled

# The snow over every app, seen over the home screen.
adb shell am start -W -n "$app/.MainActivity" --ez snow true
sleep 4
adb shell input keyevent KEYCODE_HOME
sleep 35
shot 3-over-home
adb shell dumpsys window windows > "$out/windows-overlay.txt"

# Deep Interaction: the accessibility service takes the snow over.
adb shell settings put secure enabled_accessibility_services "$app/$app.DeepService"
adb shell settings put secure accessibility_enabled 1
sleep 25
shot 4-deep
adb shell dumpsys window windows > "$out/windows-deep.txt"
adb shell dumpsys accessibility > "$out/accessibility.txt"

adb logcat -d > "$out/logcat-all.txt"
adb logcat -d -s Snowhush:V AndroidRuntime:E > "$out/logcat.txt"

status=0
if grep -q "Process: $app" "$out/logcat-all.txt"; then
    echo "the app crashed" | tee -a "$out/verdict.txt"
    status=1
fi
if ! grep -q "Snowhush" "$out/windows-overlay.txt"; then
    echo "the snow's window never came up over the apps" | tee -a "$out/verdict.txt"
    status=1
fi
if ! grep -q "Snowhush" "$out/windows-deep.txt"; then
    echo "the snow's window never came up with Deep Interaction" | tee -a "$out/verdict.txt"
    status=1
fi
if grep -q "the snow can't be drawn\|the snow stopped" "$out/logcat.txt"; then
    echo "the snow couldn't be drawn" | tee -a "$out/verdict.txt"
    status=1
fi
[ "$status" -eq 0 ] && echo "the app runs and its snow is up" | tee -a "$out/verdict.txt"
exit "$status"
