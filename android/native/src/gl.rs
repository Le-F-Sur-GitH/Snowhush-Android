//! OpenGL ES, as the thread's current context has it. The context itself is the app's business: Java makes it with
//! EGL14 on the snow's thread and keeps it current while it calls in here.

use std::ffi::c_void;
use std::sync::OnceLock;

use libloading::Library;

/// Android's own library first; the others are a desktop's, for trying the engine out there.
const LIBRARIES: [&str; 4] = ["libGLESv3.so", "libGLESv2.so", "libGLESv2.so.2", "libGLESv2.dylib"];

static LIBRARY: OnceLock<Option<Library>> = OnceLock::new();

/// The functions of the context current on this thread.
pub fn context() -> Result<glow::Context, String> {
    let library = LIBRARY.get_or_init(|| LIBRARIES.iter().find_map(|name| unsafe { Library::new(name) }.ok()));
    let Some(library) = library else { return Err("no OpenGL ES library".into()) };
    let gl = unsafe {
        glow::Context::from_loader_function(|name| {
            let mut symbol = Vec::with_capacity(name.len() + 1);
            symbol.extend_from_slice(name.as_bytes());
            symbol.push(0);
            library.get::<*const c_void>(&symbol).map_or(std::ptr::null(), |function| *function)
        })
    };
    Ok(gl)
}
