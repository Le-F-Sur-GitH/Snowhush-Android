#!/usr/bin/env python3
"""Turns Snowhush's words into the Android app's string resources.

The words the Android app shares with the other apps come from ../locales/<language>.strings, the ones only it has
from locales/<language>.strings beside this folder; each language becomes app/src/main/res/values-<language>/strings.xml.
Run it after changing either: python3 android/tools/strings.py. The resources it writes are kept in the repository, so
building the app needs no Python.
"""

import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID = os.path.dirname(HERE)
SHARED = os.path.join(os.path.dirname(ANDROID), "locales")
OWN = os.path.join(ANDROID, "locales")
RES = os.path.join(ANDROID, "app", "src", "main", "res")

# The shared words the app uses, under the names Android can give them: a resource's name has no dots.
SHARED_KEYS = [
    "intensity", "opacity", "off", "mode", "mixed", "snow", "autumn", "rain", "blossom", "halloween",
    "behavior", "display", "general", "accumulate", "surface.ground", "surface.windows",
    "melt.snow", "melt.autumn", "melt.blossom", "deep", "full_screen", "performance", "support",
    "clear.snow", "clear.autumn", "clear.blossom", "clear.snow.autumn", "clear.snow.blossom", "clear.autumn.blossom",
]
# English is what Android falls back to; Traditional Chinese has no folder and so falls back to it too, rather than
# to Simplified, as on the other platforms.
FOLDERS = {
    "en": "values", "de": "values-de", "es": "values-es", "fr": "values-fr", "ja": "values-ja",
    "pt": "values-pt", "ru": "values-ru", "uk": "values-uk", "zh-Hans": "values-b+zh+Hans",
}
ENTRY = re.compile(r'^"([^"]+)"\s*=\s*"((?:[^"\\]|\\.)*)";', re.M)


def read(path):
    with open(path, encoding="utf-8") as file:
        return dict(ENTRY.findall(file.read()))


def resource(text):
    """A .strings value as Android's resource compiler wants it."""
    text = text.replace('\\"', '"')
    text = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    text = text.replace("'", "\\'").replace('"', '\\"')
    if text[:1] in "@?":
        text = "\\" + text
    return text


def main():
    english = {**{k: v for k, v in read(os.path.join(SHARED, "en.strings")).items() if k in SHARED_KEYS}, **read(os.path.join(OWN, "en.strings"))}
    missing = [key for key in SHARED_KEYS if key not in english]
    if missing:
        sys.exit(f"en.strings has no {missing}")
    for language, folder in FOLDERS.items():
        shared = read(os.path.join(SHARED, f"{language}.strings"))
        own = read(os.path.join(OWN, f"{language}.strings"))
        words = {**{k: shared[k] for k in SHARED_KEYS if k in shared}, **own}
        if set(words) != set(english):
            sys.exit(f"{language}: missing {sorted(set(english) - set(words))}, extra {sorted(set(words) - set(english))}")
        for key, value in words.items():
            if re.findall(r"%\d\$[sd]", value) != re.findall(r"%\d\$[sd]", english[key]):
                sys.exit(f"{language}: {key} fills in other things than English does")
        lines = ['<?xml version="1.0" encoding="utf-8"?>', "<!-- Written by android/tools/strings.py from the .strings files: change those, not this. -->", "<resources>"]
        if language == "en":
            lines.append('    <string name="app_name" translatable="false">Snowhush</string>')
        for key in english:
            lines.append(f'    <string name="{key.replace(".", "_")}">{resource(words[key])}</string>')
        lines.append("</resources>")
        os.makedirs(os.path.join(RES, folder), exist_ok=True)
        with open(os.path.join(RES, folder, "strings.xml"), "w", encoding="utf-8", newline="\n") as file:
            file.write("\n".join(lines) + "\n")
    print(f"{len(english) + 1} strings in {len(FOLDERS)} languages")


if __name__ == "__main__":
    main()
