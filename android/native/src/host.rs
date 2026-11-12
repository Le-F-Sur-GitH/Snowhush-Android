//! A drawing surface without a display, for trying the engine out on a desktop: `examples/shots.rs` draws each kind
//! of weather into pictures, and `hosttest/` calls the same functions the app does, from a Java of the desktop's.
//! None of this is built into the app.

use khronos_egl as egl;

use crate::render::Target;
use crate::scene::Scene;

/// A context current on the thread that made it, drawing into memory.
pub struct Offscreen {
    egl: egl::DynamicInstance<egl::EGL1_4>,
    display: egl::Display,
    context: egl::Context,
    surface: egl::Surface,
}

impl Offscreen {
    pub fn new(width: i32, height: i32) -> Result<Offscreen, String> {
        // Mesa draws without a display when asked to.
        if std::env::var_os("EGL_PLATFORM").is_none() {
            unsafe { std::env::set_var("EGL_PLATFORM", "surfaceless") };
        }
        let egl = unsafe { egl::DynamicInstance::<egl::EGL1_4>::load_required() }.map_err(|error| format!("loading EGL: {error}"))?;
        let display = unsafe { egl.get_display(egl::DEFAULT_DISPLAY) }.ok_or("no EGL display")?;
        egl.initialize(display).map_err(|error| format!("EGL initialize: {error}"))?;
        egl.bind_api(egl::OPENGL_ES_API).map_err(|error| format!("EGL bind: {error}"))?;
        let attributes = [
            egl::SURFACE_TYPE,
            egl::PBUFFER_BIT,
            egl::RENDERABLE_TYPE,
            egl::OPENGL_ES3_BIT,
            egl::RED_SIZE,
            8,
            egl::GREEN_SIZE,
            8,
            egl::BLUE_SIZE,
            8,
            egl::ALPHA_SIZE,
            8,
            egl::NONE,
        ];
        let config = egl.choose_first_config(display, &attributes).map_err(|error| error.to_string())?.ok_or("no EGL config with alpha")?;
        let context = egl
            .create_context(display, config, None, &[egl::CONTEXT_CLIENT_VERSION, 3, egl::NONE])
            .map_err(|error| format!("EGL context: {error}"))?;
        let surface = egl
            .create_pbuffer_surface(display, config, &[egl::WIDTH, width.max(1), egl::HEIGHT, height.max(1), egl::NONE])
            .map_err(|error| format!("EGL surface: {error}"))?;
        egl.make_current(display, Some(surface), Some(surface), Some(context)).map_err(|error| format!("EGL make current: {error}"))?;
        Ok(Offscreen { egl, display, context, surface })
    }
}

impl Drop for Offscreen {
    fn drop(&mut self) {
        let _ = self.egl.make_current(self.display, None, None, None);
        let _ = self.egl.destroy_surface(self.display, self.surface);
        let _ = self.egl.destroy_context(self.display, self.context);
    }
}

/// What a scene last drew, as a PNG. A see-through surface is laid over `behind`, top and bottom, as it would lie
/// over a screen; without, it is saved as it is, see-through.
pub fn save(scene: &Scene, path: &str, behind: Option<([u8; 3], [u8; 3])>) -> Result<(), String> {
    let (Some(gpu), Some(target)) = (scene.gpu(), scene.target()) else { return Err("nothing drawn yet".into()) };
    let pixels = gpu.read(&target);
    write(path, &pixels, &target, behind)
}

fn write(path: &str, pixels: &[u8], target: &Target, behind: Option<([u8; 3], [u8; 3])>) -> Result<(), String> {
    let (width, height) = (target.width.max(1) as usize, target.height.max(1) as usize);
    let mut picture = Vec::with_capacity(width * height * 4);
    // OpenGL's rows run up the surface; a picture's run down. The colours come premultiplied.
    for row in (0..height).rev() {
        let along = 1.0 - row as f32 / height.max(2) as f32;
        for pixel in pixels[row * width * 4..(row + 1) * width * 4].chunks_exact(4) {
            let alpha = pixel[3] as f32 / 255.0;
            match behind {
                Some((top, bottom)) => {
                    for channel in 0..3 {
                        let under = top[channel] as f32 + (bottom[channel] as f32 - top[channel] as f32) * along;
                        picture.push((pixel[channel] as f32 + under * (1.0 - alpha)).round().clamp(0.0, 255.0) as u8);
                    }
                    picture.push(255);
                }
                None => {
                    for channel in 0..3 {
                        picture.push(if alpha > 0.0 { (pixel[channel] as f32 / alpha).round().clamp(0.0, 255.0) as u8 } else { 0 });
                    }
                    picture.push(pixel[3]);
                }
            }
        }
    }
    let file = std::fs::File::create(path).map_err(|error| format!("{path}: {error}"))?;
    let mut encoder = png::Encoder::new(std::io::BufWriter::new(file), width as u32, height as u32);
    encoder.set_color(png::ColorType::Rgba);
    encoder.set_depth(png::BitDepth::Eight);
    let mut writer = encoder.write_header().map_err(|error| error.to_string())?;
    writer.write_image_data(&picture).map_err(|error| error.to_string())
}

/// The same from Java, for `hosttest/`: `HostEgl.open` makes the context current on the calling thread, and keeps it
/// for as long as the test runs; `HostEgl.save` writes what the scene behind a handle last drew.
mod java {
    use std::cell::RefCell;

    use jni::JNIEnv;
    use jni::objects::{JClass, JString};
    use jni::sys::{jint, jlong, jstring};

    use super::Offscreen;

    thread_local! {
        static CONTEXT: RefCell<Option<Offscreen>> = const { RefCell::new(None) };
    }

    fn answer(env: &mut JNIEnv, result: Result<(), String>) -> jstring {
        match result {
            Ok(()) => std::ptr::null_mut(),
            Err(error) => env.new_string(error).map_or(std::ptr::null_mut(), |string| string.into_raw()),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_app_snowhush_android_HostEgl_open<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        width: jint,
        height: jint,
    ) -> jstring {
        let opened = Offscreen::new(width, height).map(|offscreen| CONTEXT.with(|context| *context.borrow_mut() = Some(offscreen)));
        answer(&mut env, opened)
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_app_snowhush_android_HostEgl_save<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        path: JString<'local>,
    ) -> jstring {
        let path: String = match env.get_string(&path) {
            Ok(path) => path.into(),
            Err(error) => return answer(&mut env, Err(error.to_string())),
        };
        let saved = crate::with(handle, Err("the scene is gone".to_string()), |scene| super::save(scene, &path, Some(([36, 44, 62], [18, 20, 28]))));
        answer(&mut env, saved)
    }
}
