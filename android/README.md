# Snowhush for Android

An unofficial Android port of [Snowhush](https://snowhush.app) by Mykhailo Bezruchko. It is the same snow: the
simulation the Windows and Linux apps share (`../core`), drawn with the Linux app's OpenGL ES shaders
(`../linux/src/shaders`), in a plain Java app with no libraries. Android 8 or later, OpenGL ES 3.

The latest build is on the repository's [releases page](../../../releases/tag/latest).

## What a phone gets

A phone has no windows to drag and no cursor, and Android lets an app onto the screen in other ways than a desktop
does. So the snow has three places to fall, and they share one set of settings.

- **Over your apps.** A see-through window over everything, which touches go straight through. It needs "Display
  over other apps". Android only lets touches through such a window while it is at most four fifths opaque, so the
  snow is drawn at that strength. It sits above the apps and under the keyboard, the bars and the lock screen, lies
  on top of a navigation bar with buttons as it does on a taskbar, and fades out of a full-screen app's way unless
  told to show over full screen.
- **Deep Interaction.** With Snowhush turned on as an accessibility service, Android tells it where the windows are,
  which is what the desktop apps' window trackers ask their systems. The snow then settles on the keyboard, on a
  picture-in-picture window and on the lower half of a split screen, drops when they go, and is drawn at full
  strength, above everything. Only the windows' places and sizes are read, never what they show.
- **Live wallpaper.** The snow behind the apps, on the home and lock screens, with no permission at all. The finger
  sweeps the drift as a cursor does, and the home screen's pages sliding past drag the air along as a desktop's
  spaces do. Behind it is the night under a street lamp, or a picture of your own.

What stands in for the rest of a desktop:

| On a desktop | On Android |
| --- | --- |
| Menu bar or tray icon | The app's one screen, and a Quick Settings tile |
| Cursor pushing through the drift | A finger, on the wallpaper and in the app |
| Dragging or shaking a window | Tilting the phone blows the snowfall, shaking it blows the drift away |
| Windows and the Dock | The keyboard, picture-in-picture, a split screen, the navigation bar |
| A sliding desktop's wind | The home screen's pages |
| Shadows tinted by the wallpaper | The same, from the colours Android takes from the wallpaper |
| Less on battery, paused when asleep or locked | The same, from battery saver, charging, and the screen |
| Open at Login | Open at Startup |

The app's own screen snows too. Its cards are the snow's windows: it settles on them, slides off as they scroll,
and waits for a card scrolled out of sight.

Not here: hiding from screenshots, which Android gives an app no way to do, and checking for updates, since the app
has no network access at all.

## Permissions

| Permission | Why |
| --- | --- |
| Display over other apps | The snow over your apps |
| Foreground service, notifications | Android only keeps that window up behind a notification |
| Run at startup | Open at Startup |
| Accessibility service, optional | Deep Interaction |

The app asks for no network permission, so nothing it reads can leave the phone.

Android 13 and later hold back "Display over other apps" and accessibility for an app installed from a file. If
Android calls either a restricted setting, open the app's info page, tap ⋮, then *Allow restricted settings*.

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
  Android knows. `OverlayWindow` is the window over every app, put up by `OverlayService` or by `DeepService`, the
  accessibility service. `SnowWallpaper` is the live wallpaper, `MainActivity` the screen, `SnowTile` the tile.
- `locales/` has the words only Android needs, in the nine languages of `../locales`; `tools/strings.py` turns both
  into string resources, and `tools/icon.py` draws the adaptive icon after `../scripts/make-icon.swift`.

## Trying it without a phone

```
cargo test --manifest-path android/native/Cargo.toml
cargo run --release --features host --example shots --manifest-path android/native/Cargo.toml -- shots
android/hosttest/run.sh
```

The second draws each kind of weather as a phone would show it into PNGs, with Mesa and no display. The third
calls the engine from a desktop's Java through the same `Native` class the app uses, so a native method that Rust
names or shapes differently fails there instead of on a phone. `tools/smoke.sh` installs the APK on an emulator or
a phone over adb, turns the snow on and keeps screenshots.

To turn the snow over every app on or off from a script:

```
adb shell am start -n app.snowhush.android/.MainActivity --ez snow true
```

## License

As Snowhush itself: the [PolyForm Noncommercial License 1.0.0](../LICENSE). Free for any noncommercial purpose.
