//! Snowhush for Android: the simulation the Windows and Linux apps share, drawn with the Linux app's shaders, behind
//! a handful of calls the Java side makes through JNI. Java owns everything Android: the surfaces, their graphics
//! contexts, the windows on the screen, the settings. Each surface has a `Scene` here, used from its own thread only.

pub mod gl;
pub mod light;
pub mod render;
pub mod scene;

#[cfg(feature = "host")]
pub mod host;

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Mutex, Once};

use jni::JNIEnv;
use jni::objects::{JByteBuffer, JClass, JFloatArray};
use jni::sys::{jboolean, jfloat, jint, jlong, jstring};

use snowhush_core::simulation::Mode;

use render::Backdrop;
use scene::{PowerBudget, Reported, Scene};

/// What `Native.set` sets, as `Native.java` numbers them.
mod key {
    pub const INTENSITY: i32 = 0;
    pub const OPACITY: i32 = 1;
    pub const SURFACES: i32 = 2;
    pub const MELT: i32 = 3;
    pub const MODES: i32 = 4;
    pub const HALLOWEEN: i32 = 5;
    pub const POWER: i32 = 6;
    pub const RATE: i32 = 7;
    pub const HOUR: i32 = 8;
    pub const POINTER: i32 = 9;
    pub const CLEAR: i32 = 10;
    pub const WIND: i32 = 11;
    pub const SLIDE: i32 = 12;
    pub const COVERED: i32 = 13;
    pub const SHADOW: i32 = 14;
    pub const BACKDROP: i32 = 15;
    pub const RESUME: i32 = 16;
}

const MODES: [Mode; 4] = [Mode::Snow, Mode::Autumn, Mode::Rain, Mode::Blossom];

/// The kinds falling, from a mask with a bit for each of `MODES` in turn, the one picked `last` after the other.
pub fn modes_of(mask: u32, last: i32) -> Vec<Mode> {
    let mut modes: Vec<Mode> = MODES.iter().enumerate().filter(|&(k, _)| mask & (1 << k) != 0).map(|(_, &mode)| mode).collect();
    if let Some(last) = usize::try_from(last).ok().and_then(|k| MODES.get(k))
        && let Some(at) = modes.iter().position(|mode| mode == last)
    {
        let picked = modes.remove(at);
        modes.push(picked);
    }
    modes
}

/// A scene and whether something in it went wrong for good.
struct Held {
    scene: Scene,
    broken: bool,
}

/// What went wrong last, for the log.
static FAILURE: Mutex<Option<String>> = Mutex::new(None);
static HOOK: Once = Once::new();

fn failed(text: String) {
    if let Ok(mut failure) = FAILURE.lock() {
        *failure = Some(text);
    }
}

/// Runs `body` on the scene behind `handle`. A panic stops that scene's snow for good instead of taking the app, the
/// wallpaper or the accessibility service down with it: the calls after it do nothing and `frame` says so.
fn with<T>(handle: jlong, otherwise: T, body: impl FnOnce(&mut Scene) -> T) -> T {
    if handle == 0 {
        return otherwise;
    }
    let held = unsafe { &mut *(handle as *mut Held) };
    if held.broken {
        return otherwise;
    }
    match catch_unwind(AssertUnwindSafe(|| body(&mut held.scene))) {
        Ok(value) => value,
        Err(_) => {
            held.broken = true;
            otherwise
        }
    }
}

fn text(env: &mut JNIEnv, text: &str) -> jstring {
    env.new_string(text).map_or(std::ptr::null_mut(), |string| string.into_raw())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_snowhush_android_Native_create<'local>(_env: JNIEnv<'local>, _class: JClass<'local>) -> jlong {
    HOOK.call_once(|| {
        std::panic::set_hook(Box::new(|info| failed(info.to_string())));
    });
    Box::into_raw(Box::new(Held { scene: Scene::new(), broken: false })) as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_snowhush_android_Native_destroy<'local>(_env: JNIEnv<'local>, _class: JClass<'local>, handle: jlong) {
    if handle != 0 {
        let held = unsafe { Box::from_raw(handle as *mut Held) };
        let _ = catch_unwind(AssertUnwindSafe(move || drop(held)));
    }
}

/// Makes what the snow is drawn with, in the context current on this thread. Returns what went wrong, or nothing.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_snowhush_android_Native_glInit<'local>(mut env: JNIEnv<'local>, _class: JClass<'local>, handle: jlong) -> jstring {
    match with(handle, Err("the scene is gone".to_string()), |scene| scene.gl_init()) {
        Ok(()) => std::ptr::null_mut(),
        Err(error) => text(&mut env, &error),
    }
}

/// The context is gone, or about to go.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_snowhush_android_Native_glDrop<'local>(_env: JNIEnv<'local>, _class: JClass<'local>, handle: jlong) {
    with(handle, (), |scene| scene.gl_drop());
}

/// The surface's size in pixels, what a navigation bar with buttons takes of its left, right and bottom edges, and
/// how many pixels a point takes.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_snowhush_android_Native_place<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    width: jint,
    height: jint,
    left: jint,
    right: jint,
    bottom: jint,
    scale: jfloat,
) {
    with(handle, (), |scene| scene.place(width, height, left, right, bottom, scale));
}

/// Steps the snow to `now`, in nanoseconds, and draws it if it changed. Returns twice the nanoseconds until the next
/// frame is due, plus one when something was drawn and the surface wants showing; below zero when the scene is broken.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_snowhush_android_Native_frame<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    now: jlong,
) -> jlong {
    with(handle, -1, |scene| {
        let frame = scene.frame(now);
        frame.wait.max(0) * 2 + frame.drew as jlong
    })
}

/// One setting or happening, numbered as in `key`, with up to four numbers to it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_snowhush_android_Native_set<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    what: jint,
    a: jfloat,
    b: jfloat,
    c: jfloat,
    d: jfloat,
) {
    with(handle, (), |scene| set(scene, what, a, b, c, d));
}

pub fn set(scene: &mut Scene, what: i32, a: f32, b: f32, c: f32, d: f32) {
    let on = |value: f32| value != 0.0;
    match what {
        key::INTENSITY => scene.set_intensity(a),
        key::OPACITY => scene.set_opacity(a),
        key::SURFACES => scene.set_surfaces(on(a), on(b)),
        key::MELT => scene.set_melt(on(a)),
        // The kinds as a mask, the one picked last, whether what lies is cleared away first, and whether calmly.
        key::MODES => scene.set_modes(&modes_of(a as u32, b as i32), on(c), on(d)),
        key::HALLOWEEN => scene.set_halloween(on(a)),
        key::POWER => scene.set_power(match a as i32 {
            0 => PowerBudget::Full,
            2 => PowerBudget::Saver,
            _ => PowerBudget::Battery,
        }),
        key::RATE => scene.set_rate(a, on(b)),
        key::HOUR => scene.set_hour(a),
        key::POINTER => scene.set_pointer(on(c).then_some((a, b))),
        key::CLEAR => scene.clear(on(a), b),
        key::WIND => scene.set_wind(a),
        key::SLIDE => scene.slide(a),
        key::COVERED => scene.set_covered(on(a)),
        key::SHADOW => scene.set_shadow(on(d).then_some([a, b, c])),
        key::BACKDROP => scene.set_backdrop(match a as i32 {
            1 => Backdrop::Night,
            2 => Backdrop::Picture { slide: b.clamp(0.0, 1.0), dim: c.clamp(0.0, 1.0) },
            _ => Backdrop::Clear,
        }),
        key::RESUME => scene.resume(),
        _ => {}
    }
}

/// The windows on the screen, front to back, `Reported::NUMBERS` numbers each; `moving` while one is dragged or
/// scrolled.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_snowhush_android_Native_windows<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    windows: JFloatArray<'local>,
    count: jint,
    moving: jboolean,
) {
    let mut numbers = vec![0.0f32; count.max(0) as usize * Reported::NUMBERS];
    if !numbers.is_empty() && (windows.is_null() || env.get_float_array_region(&windows, 0, &mut numbers).is_err()) {
        // Shorter than it said: an exception is pending, and Java hears of it on return.
        return;
    }
    let reported: Vec<Reported> = numbers.chunks_exact(Reported::NUMBERS).map(Reported::read).collect();
    with(handle, (), |scene| scene.set_windows(reported, moving != 0));
}

/// A wallpaper's picture, as rows of red, green, blue and alpha bytes from the top in a direct buffer, or none to
/// take it away. It is kept in the graphics context: call again once a new one is made.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_snowhush_android_Native_picture<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    pixels: JByteBuffer<'local>,
    width: jint,
    height: jint,
) {
    if pixels.is_null() || width <= 0 || height <= 0 {
        with(handle, (), |scene| scene.set_picture(None));
        return;
    }
    let (Ok(address), Ok(capacity)) = (env.get_direct_buffer_address(&pixels), env.get_direct_buffer_capacity(&pixels)) else { return };
    if address.is_null() || capacity < width as usize * height as usize * 4 {
        return;
    }
    let bytes = unsafe { std::slice::from_raw_parts(address, width as usize * height as usize * 4) };
    with(handle, (), |scene| scene.set_picture(Some((bytes, width as u32, height as u32))));
}

/// What the scene is and how it is doing, and what went wrong last if anything did, for the log.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_snowhush_android_Native_describe<'local>(mut env: JNIEnv<'local>, _class: JClass<'local>, handle: jlong) -> jstring {
    let mut described = with(handle, "broken".to_string(), |scene| scene.describe());
    if let Ok(mut failure) = FAILURE.lock()
        && let Some(failure) = failure.take()
    {
        described.push_str("; failed: ");
        described.push_str(&failure);
    }
    text(&mut env, &described)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_snowhush_android_Native_version<'local>(mut env: JNIEnv<'local>, _class: JClass<'local>) -> jstring {
    text(&mut env, env!("CARGO_PKG_VERSION"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn modes_come_in_the_order_picked() {
        assert_eq!(modes_of(0b0001, 0), vec![Mode::Snow]);
        assert_eq!(modes_of(0b0101, 0), vec![Mode::Rain, Mode::Snow]);
        assert_eq!(modes_of(0b0101, 2), vec![Mode::Snow, Mode::Rain]);
        assert_eq!(modes_of(0b1000, -1), vec![Mode::Blossom]);
        assert!(modes_of(0, 0).is_empty());
    }

    #[test]
    fn settings_reach_the_scene() {
        let mut scene = Scene::new();
        set(&mut scene, key::INTENSITY, 0.25, 0.0, 0.0, 0.0);
        set(&mut scene, key::OPACITY, 0.6, 0.0, 0.0, 0.0);
        set(&mut scene, key::SURFACES, 1.0, 0.0, 0.0, 0.0);
        set(&mut scene, key::MELT, 0.0, 0.0, 0.0, 0.0);
        set(&mut scene, key::MODES, 0b0110 as f32, 1.0, 0.0, 0.0);
        set(&mut scene, key::HALLOWEEN, 1.0, 0.0, 0.0, 0.0);
        let settings = scene.settings();
        assert_eq!((settings.intensity, settings.opacity), (0.25, 0.6));
        assert!(settings.ground && !settings.ledges && !settings.melt && settings.halloween);
        assert_eq!(settings.modes, vec![Mode::Rain, Mode::Autumn]);
        assert!(!scene.halloween());
    }

    #[test]
    fn a_panic_breaks_only_its_scene() {
        let handle = Box::into_raw(Box::new(Held { scene: Scene::new(), broken: false })) as jlong;
        let hook = std::panic::take_hook();
        std::panic::set_hook(Box::new(|_| {}));
        let answer = with(handle, 7, |_| -> i32 { panic!("as a bug would") });
        std::panic::set_hook(hook);
        assert_eq!(answer, 7);
        assert_eq!(with(handle, 0, |_| 1), 0, "a broken scene does nothing more");
        drop(unsafe { Box::from_raw(handle as *mut Held) });
    }
}
