#!/bin/sh
# Tries the engine out without a phone: builds it for this computer with a drawing surface of its own, checks that
# it has every native method the app declares, then runs HostTest against it. Needs Rust, a JDK, an OpenGL ES 3
# that can draw without a display (Mesa does), and ANDROID_JAR pointing at an android.jar, to compile Native.java.
# OutlineTest, which needs none of that but the JDK, tries the finding of icons' and widgets' outlines in a picture.
set -eu
cd "$(dirname "$0")"
# The newest platform the Android SDK has, unless told which.
if [ -z "${ANDROID_JAR:-}" ] && [ -n "${ANDROID_HOME:-}" ]; then
    ANDROID_JAR=$(ls -d "$ANDROID_HOME"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -n 1)
fi
: "${ANDROID_JAR:=}"
[ -f "$ANDROID_JAR" ] || { echo "ANDROID_JAR should point at an android.jar" >&2; exit 1; }
sources=../app/src/main/java/app/snowhush/android
out=out
rm -rf "$out" && mkdir -p "$out/classes" "$out/headers"

cargo build --locked --features host --manifest-path ../native/Cargo.toml
library=../native/target/debug

javac -proc:none -cp "$ANDROID_JAR" -d "$out/classes" -h "$out/headers" "$sources/Native.java" "$sources/Prefs.java" "$sources/Outline.java" \
    HostEgl.java HostTest.java OutlineTest.java
java -cp "$out/classes" app.snowhush.android.OutlineTest

# Every native method Java declares has to be in the library under the name JNI will look for.
wanted=$(grep -ho 'Java_[A-Za-z0-9_]*' "$out"/headers/*.h | sort -u)
had=$(nm -D --defined-only "$library/libsnowhush.so" | grep -o 'Java_[A-Za-z0-9_]*' | sort -u)
for symbol in $wanted; do
    echo "$had" | grep -qx "$symbol" || { echo "libsnowhush.so has no $symbol" >&2; exit 1; }
done

java -Djava.library.path="$library" -cp "$out/classes" app.snowhush.android.HostTest "$out"
