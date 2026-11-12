# Snowhush for Android

An unofficial Android port of [Snowhush](https://snowhush.app) by Mykhailo Bezruchko. It is the same snow: the
simulation the Windows and Linux apps share (`../core`), drawn with the Linux app's OpenGL ES shaders
(`../linux/src/shaders`), in a plain Java app with no libraries and no permissions. Android 8 or later, OpenGL ES 3.

The latest build is on the repository's [releases page](../../../releases/tag/latest).

## What a phone gets

The snow is a **live wallpaper**: it falls on the home and lock screens, behind the apps, and nowhere else. It asks
for no permission. The finger sweeps the drift as a cursor does, and the home screen's pages sliding past drag the
air along as a desktop's spaces do. Behind it is the night under a street lamp, or a picture of your own.

A phone has no windows for the snow to lie on. It has widgets and app icons, and with **Deep Interaction** they are
the snow's windows: it settles along their tops, follows their rounded corners, and waits for the ones on another
page as it waits for a window on another desktop. Deep Interaction is an accessibility service, because that is the
one thing Android tells where the widgets and icons are. It listens to the launcher alone, only while the
wallpaper is in sight, and reads how the home screen is laid out and nothing else.

Two things are a wallpaper's own:

- The home screen draws its icons over the wallpaper, so the snow falls behind them. A flake crossing an icon's top
  leaves its snow there and falls on, and the icon under it gets its share too (`falls_behind` in the core).
- A wallpaper is out of sight most of the time. While the screen is on, the snow goes on falling unseen behind
  whatever app is in front, so what settled meanwhile is there when the home screen is back. With the screen off,
  nothing falls and nothing runs. A page turned to for the first time has been snowed on as long as the others.

What stands in for the rest of a desktop:

| On a desktop | On Android |
| --- | --- |
| Menu bar or tray icon | The app's one screen |
| Windows and the Dock | The home screen's widgets and app icons |
| Cursor pushing through the drift | A finger, on the home screen and in the app |
| Dragging or shaking a window | Tilting the phone blows the snowfall, shaking it blows the drift away |
| A sliding desktop's wind | The home screen's pages |
| A window on another desktop | An icon on another page, or behind the app drawer |
| Less on battery, paused when asleep or covered | The same, from battery saver, charging, and the screen |

The app's own screen snows too, as a taste of the wallpaper: its cards are the snow's windows.

Launchers lay their icons out each their own way, and most say only where an icon and its name are together, not
where the picture is. Snowhush works that out, and *Snow Height on Apps* and *Snow Width on Apps* nudge the snow
until it sits right. To fit a launcher it misreads, hold the last line of the app's screen down: it hands over what
Deep Interaction found on the home screen, sizes and places and nothing of what it shows.

Not here: snow over other apps. Android only lets touches through a window over another app while that window is
mostly see-through, and a phone's screen is for the app in front. Hiding from screenshots and checking for updates
aren't here either: the app has no network access at all.

## Permissions

None. A live wallpaper needs none, and the app declares none: nothing it reads can leave the phone.

Deep Interaction is turned on in Android's accessibility settings. Android 13 and later hold that back for an app
installed from a file: if Android calls it a restricted setting, open the app's info page, tap ⋮, then *Allow
restricted settings*.

## Building

GitHub builds the APK on every push to `main` (`.github/workflows/android.yml`), tries it on an emulator and
publishes it as the `latest` release.

On your own machine you need the Android SDK and NDK, a JDK 17, Rust and [cargo-ndk](https://github.com/bbqsrc/cargo-ndk):

```
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
cd android
./gradlew assembleRelease
```

Gradle runs cargo-ndk itself and puts `libsnowhush.so` into the APK, which lands in
`app/build/outputs/apk/release/`. `-Psnowhush.abis=arm64-v8a` builds for one processor only. Opening `android/` in
Android Studio works the same way.

Every build is signed with the key in `snowhush-debug.keystore`, so a newer build installs over an older one
wherever each was made. That key is public and proves nothing about who built an APK; to hand the app out for
real, point `SNOWHUSH_KEYSTORE` and its companions (see `app/build.gradle`) at a key of your own.

## How it is put together

- `native/` is the engine, in Rust: `scene.rs` keeps one surface's snow, as `linux/src/screen.rs` does for a display,
  `render.rs` is the Linux renderer drawing at the phone's own resolution into whatever context is current, and
  `lib.rs` is the handful of calls Java makes. It builds for a desktop too.
- `app/` is the app. `SnowEngine` owns a surface's thread, its EGL context and its scene, and tells the scene what
  Android knows. `SnowWallpaper` is the live wallpaper, `DeepService` the accessibility service that reads the home
  screen, `MainActivity` the screen.
- `locales/` has the words only Android needs, in the nine languages of `../locales`; `tools/strings.py` turns both
  into string resources, and `tools/icon.py` draws the adaptive icon after `../scripts/make-icon.swift`.

## Trying it without a phone

```
cargo test --manifest-path android/native/Cargo.toml
cargo run --release --features host --example shots --manifest-path android/native/Cargo.toml -- shots
android/hosttest/run.sh
```

The second draws each kind of weather as a phone would show it into PNGs, with Mesa and no display, a home screen
with its widget and icons among them. The third calls the engine from a desktop's Java through the same `Native`
class the app uses, so a native method that Rust names or shapes differently fails there instead of on a phone.
`tools/smoke.sh` installs the APK on an emulator or a phone over adb, sets the wallpaper, turns Deep Interaction on
and keeps screenshots; `adb logcat -s Snowhush` says what the app found.

## License

As Snowhush itself: the [PolyForm Noncommercial License 1.0.0](../LICENSE). Free for any noncommercial purpose.
