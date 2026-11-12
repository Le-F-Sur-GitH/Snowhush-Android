//! Draws the snow with OpenGL ES 3, the same passes as the Linux renderer and with its shaders: Halloween's pumpkins,
//! the piles, read from a height texture, the ghosts' signs, then every flake, grain, puff and ghost as an instanced
//! quad, and rain's drops on the glass, added up into a field first and drawn from it as glass.
//!
//! Unlike on a desktop, where one pixel per point is close enough to the panel's own, a phone is held near and its
//! pixels are small: the snow is drawn at the surface's own resolution, `scale` pixels to the simulation's point, in
//! the part of the surface the snow has. A live wallpaper has nothing behind it, so a backdrop goes down first.

use std::mem::size_of;

use glow::HasContext;

use snowhush_core::geometry::V2;
use snowhush_core::halloween::{self, PUMPKIN_ASPECT, PUMPKIN_CANDLE, SIGN_CAPTIONS};
use snowhush_core::simulation::{COLUMN_WIDTH, PumpkinSprite, SnowSimulation, Sprite};

use crate::gl;

const SPRITE_VERTEX: &str = include_str!("../../../linux/src/shaders/sprite.vert");
const SPRITE_FRAGMENT: &str = include_str!("../../../linux/src/shaders/sprite.frag");
const PILE_VERTEX: &str = include_str!("../../../linux/src/shaders/pile.vert");
const PILE_FRAGMENT: &str = include_str!("../../../linux/src/shaders/pile.frag");
const SIGN_VERTEX: &str = include_str!("../../../linux/src/shaders/sign.vert");
const SIGN_FRAGMENT: &str = include_str!("../../../linux/src/shaders/sign.frag");
const PUMPKIN_VERTEX: &str = include_str!("../../../linux/src/shaders/pumpkin.vert");
const PUMPKIN_FRAGMENT: &str = include_str!("../../../linux/src/shaders/pumpkin.frag");
const FIELD_FRAGMENT: &str = include_str!("../../../linux/src/shaders/field.frag");
const GLASS_VERTEX: &str = include_str!("../../../linux/src/shaders/glass.vert");
const GLASS_FRAGMENT: &str = include_str!("../../../linux/src/shaders/glass.frag");
/// A drop on the glass, as both the sprites and the field draw it, put into each where it is included.
const DROP: &str = include_str!("../../../linux/src/shaders/drop.glsl");
const LEAVES: &str = include_str!("../../../linux/src/shaders/leaves.glsl");
const BACKDROP_VERTEX: &str = include_str!("shaders/backdrop.vert");
const BACKDROP_FRAGMENT: &str = include_str!("shaders/backdrop.frag");
/// A quad over the snow's part of the surface, drawn with a blend that leaves what is there at the given opacity.
const FADE_VERTEX: &str = "#version 300 es
void main() {
    gl_Position = vec4(float(gl_VertexID & 1) * 2.0 - 1.0, float(gl_VertexID >> 1) * 2.0 - 1.0, 0.0, 1.0);
}
";
const FADE_FRAGMENT: &str = "#version 300 es
precision mediump float;
out vec4 color;
void main() {
    color = vec4(0.0);
}
";
/// The ghosts' signs, one picture per caption at two pixels per point, the stick's foot at the middle of the bottom edge.
const SIGNS: [&[u8]; SIGN_CAPTIONS.len()] = [
    include_bytes!("../../../windows/assets/signs/sign-00.png"),
    include_bytes!("../../../windows/assets/signs/sign-01.png"),
    include_bytes!("../../../windows/assets/signs/sign-02.png"),
    include_bytes!("../../../windows/assets/signs/sign-03.png"),
    include_bytes!("../../../windows/assets/signs/sign-04.png"),
    include_bytes!("../../../windows/assets/signs/sign-05.png"),
    include_bytes!("../../../windows/assets/signs/sign-06.png"),
    include_bytes!("../../../windows/assets/signs/sign-07.png"),
    include_bytes!("../../../windows/assets/signs/sign-08.png"),
    include_bytes!("../../../windows/assets/signs/sign-09.png"),
    include_bytes!("../../../windows/assets/signs/sign-10.png"),
    include_bytes!("../../../windows/assets/signs/sign-11.png"),
];
/// The jack-o'-lantern's shell and its carved face, apart so the candle flickers in the face alone, at two pixels per
/// unit of a drawing 100 units wide.
const PUMPKIN: [&[u8]; 2] =
    [include_bytes!("../../../windows/assets/lanterns/shell.png"), include_bytes!("../../../windows/assets/lanterns/face.png")];
/// How far the finger's candle reaches, in points.
pub const LANTERN_REACH: f32 = 130.0;
/// The height texture's width; the pile shader wraps its index at the same width.
const ROW: usize = 4096;
const OCCLUDERS: usize = 16;
/// The night a wallpaper without a picture shows: the icon's, top and bottom.
const NIGHT_SKY: [f32; 3] = [0.133, 0.106, 0.082];
const NIGHT_GROUND: [f32; 3] = [0.055, 0.047, 0.039];
/// Its street lamp: how far across the surface it hangs, how far below the top in points, clear of the status bar,
/// how far its glow reaches in points, and how bright it is.
const LAMP: [f32; 4] = [0.66, 104.0, 150.0, 1.0];

/// The light the snow is drawn in.
#[derive(Clone, Copy)]
pub struct Ambient {
    pub light: [f32; 3],
    pub shadow_tint: [f32; 4],
    /// How far into Halloween night it is: moonlit snow, a candle's glow along the drifts' edges and one at the finger.
    pub night: f32,
    /// Where the finger's candle is, in the simulation's points.
    pub lantern: V2,
}

/// Where the snow is drawn: the whole surface, in pixels, and the part of it the snow has, from the surface's bottom
/// left, which leaves out a navigation bar the snow lies on top of. `scale` is how many pixels a point takes.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Target {
    pub width: i32,
    pub height: i32,
    pub x: i32,
    pub y: i32,
    pub w: i32,
    pub h: i32,
    pub scale: f32,
}

/// What is behind the snow.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Backdrop {
    /// Whatever the screen shows: the surface is see-through.
    Clear,
    /// The night under a street lamp.
    Night,
    /// The picture handed to `Gpu::set_picture`, cropped to fill; `slide` from 0 to 1 is how far along a picture wider
    /// than the surface the view is, as the home screen's pages go by, and `dim` how much of its light is kept.
    Picture { slide: f32, dim: f32 },
}

struct Program {
    program: glow::Program,
    uniforms: Vec<(String, Option<glow::UniformLocation>)>,
}

impl Program {
    fn location(&self, name: &str) -> Option<&glow::UniformLocation> {
        self.uniforms.iter().find(|(n, _)| n == name).and_then(|(_, location)| location.as_ref())
    }
}

pub struct Gpu {
    gl: glow::Context,
    sprite: Program,
    pile: Program,
    sign: Program,
    pumpkin: Program,
    fade: Program,
    backdrop: Program,
    /// Drops on the glass: their bumps added up into a field, and the glass drawn from it.
    field: Program,
    glass: Program,
    /// Whether the graphics driver can draw into a half-float texture, as the field needs; without, drops on the
    /// glass are drawn as sprites.
    fields_drawable: bool,
    /// The field, as big as the surface, while it has drops on its glass.
    field_target: Option<(glow::Texture, glow::Framebuffer, (i32, i32))>,
    sprite_array: glow::VertexArray,
    sprite_buffer: glow::Buffer,
    /// Holds no attributes: the corners of the piles, the signs and the pumpkins come from the vertex index alone.
    empty_array: glow::VertexArray,
    heights: glow::Texture,
    /// Rows the height texture has room for; it is only made again when that changes.
    height_rows: usize,
    sprites: Vec<Sprite>,
    columns: Vec<f32>,
    /// Each caption's sign, made the first time a ghost holds it up, and its size in points.
    signs: Vec<Option<(glow::Texture, [f32; 2])>>,
    /// The pumpkin's shell and face, made the first time one is drawn.
    pumpkin_pictures: [Option<glow::Texture>; 2],
    /// A wallpaper's picture and its size in pixels.
    picture: Option<(glow::Texture, [u32; 2])>,
    pub adapter: String,
}

impl Gpu {
    /// Makes what the snow is drawn with, in the context current on this thread.
    pub fn new() -> Result<Gpu, String> {
        let gl = gl::context()?;
        unsafe {
            let adapter = format!("{} ({})", gl.get_parameter_string(glow::RENDERER), gl.get_parameter_string(glow::VERSION));
            let included = |source: &str| source.replace("#include \"drop.glsl\"", DROP).replace("#include \"leaves.glsl\"", LEAVES);
            let sprite_vertex = packed(SPRITE_VERTEX);
            let sprite = program(
                &gl,
                &sprite_vertex,
                &included(SPRITE_FRAGMENT),
                &[
                    "viewSize",
                    "scale",
                    "time",
                    "light",
                    "shadowTint",
                    "rim",
                    "lantern",
                    "pumpkins",
                    "shake",
                    "panel",
                    "autumn",
                    "blossom",
                    "wilt",
                    "glass",
                ],
            )?;
            let field =
                program(&gl, &sprite_vertex, &included(FIELD_FRAGMENT), &["viewSize", "scale", "time", "lantern", "pumpkins", "shake", "panel"])?;
            let glass = program(&gl, GLASS_VERTEX, GLASS_FRAGMENT, &["field", "light", "scale"])?;
            let extensions = gl.supported_extensions();
            let fields_drawable = extensions.contains("GL_EXT_color_buffer_float") || extensions.contains("GL_EXT_color_buffer_half_float");
            let pile = program(
                &gl,
                PILE_VERTEX,
                &included(PILE_FRAGMENT),
                &[
                    "viewSize",
                    "scale",
                    "time",
                    "columnWidth",
                    "columnCount",
                    "pileTop",
                    "occluderCount",
                    "origin",
                    "pileBottom",
                    "heightOffset",
                    "light",
                    "shadowTint",
                    "rim",
                    "lantern",
                    "pumpkins",
                    "shake",
                    "occluders",
                    "heights",
                    "panel",
                    "wilt",
                    "drift",
                ],
            )?;
            let sign = program(
                &gl,
                SIGN_VERTEX,
                SIGN_FRAGMENT,
                &["viewSize", "shake", "anchor", "size", "angle", "time", "ground", "light", "lantern", "pumpkins", "panel", "picture"],
            )?;
            let pumpkin = program(
                &gl,
                PUMPKIN_VERTEX,
                PUMPKIN_FRAGMENT,
                &["viewSize", "shake", "anchor", "size", "wick", "seed", "time", "light", "night", "panel", "shell", "face"],
            )?;
            let fade = program(&gl, FADE_VERTEX, FADE_FRAGMENT, &[])?;
            let backdrop =
                program(&gl, BACKDROP_VERTEX, BACKDROP_FRAGMENT, &["picture", "pictured", "crop", "dim", "sky", "ground", "size", "lamp"])?;

            let sprite_array = gl.create_vertex_array()?;
            let sprite_buffer = gl.create_buffer()?;
            gl.bind_vertex_array(Some(sprite_array));
            gl.bind_buffer(glow::ARRAY_BUFFER, Some(sprite_buffer));
            let stride = size_of::<Sprite>() as i32;
            // Position, velocity, then size, alpha, soft and tint amount as one, see `packed`, tint, lump, ghost, pose,
            // mood, leaf, spin, rain, drop, petal: see `Sprite`.
            for (location, components, offset) in [
                (0, 2, 0),
                (1, 2, 8),
                (2, 4, 16),
                (6, 3, 32),
                (7, 1, 44),
                (8, 4, 48),
                (9, 4, 64),
                (10, 2, 80),
                (11, 3, 88),
                (12, 2, 100),
                (13, 2, 108),
                (14, 4, 116),
                (15, 1, 132),
            ] {
                gl.enable_vertex_attrib_array(location);
                gl.vertex_attrib_pointer_f32(location, components, glow::FLOAT, false, stride, offset);
                gl.vertex_attrib_divisor(location, 1);
            }
            let empty_array = gl.create_vertex_array()?;
            gl.bind_vertex_array(None);

            let heights = gl.create_texture()?;
            gl.bind_texture(glow::TEXTURE_2D, Some(heights));
            sampling(&gl, glow::NEAREST, glow::NEAREST);

            Ok(Gpu {
                gl,
                sprite,
                pile,
                sign,
                pumpkin,
                fade,
                backdrop,
                field,
                glass,
                fields_drawable,
                field_target: None,
                sprite_array,
                sprite_buffer,
                empty_array,
                heights,
                height_rows: 0,
                sprites: Vec::new(),
                columns: Vec::new(),
                signs: vec![None; SIGNS.len()],
                pumpkin_pictures: [None; 2],
                picture: None,
                adapter,
            })
        }
    }

    /// A wallpaper's picture, as rows of red, green, blue and alpha bytes from the top, or none to take it away.
    pub fn set_picture(&mut self, picture: Option<(&[u8], u32, u32)>) {
        let gl = &self.gl;
        unsafe {
            if let Some((texture, _)) = self.picture.take() {
                gl.delete_texture(texture);
            }
            let Some((pixels, width, height)) = picture else { return };
            if width == 0 || height == 0 || pixels.len() < width as usize * height as usize * 4 {
                return;
            }
            let Ok(texture) = gl.create_texture() else { return };
            gl.active_texture(glow::TEXTURE0);
            gl.bind_texture(glow::TEXTURE_2D, Some(texture));
            sampling(gl, glow::LINEAR_MIPMAP_LINEAR, glow::LINEAR);
            gl.tex_image_2d(
                glow::TEXTURE_2D,
                0,
                glow::RGBA8 as i32,
                width as i32,
                height as i32,
                0,
                glow::RGBA,
                glow::UNSIGNED_BYTE,
                glow::PixelUnpackData::Slice(Some(&pixels[..width as usize * height as usize * 4])),
            );
            gl.generate_mipmap(glow::TEXTURE_2D);
            self.picture = Some((texture, [width, height]));
        }
    }

    pub fn has_picture(&self) -> bool {
        self.picture.is_some()
    }

    /// Shows nothing of the snow, as when the screen is covered: only what is behind it.
    pub fn clear(&self, target: &Target, backdrop: Backdrop) {
        unsafe {
            self.begin(target, backdrop);
        }
    }

    /// The surface cleared, with its backdrop down, ready for the snow in its part of it.
    unsafe fn begin(&self, target: &Target, backdrop: Backdrop) {
        let gl = &self.gl;
        unsafe {
            gl.bind_framebuffer(glow::FRAMEBUFFER, None);
            gl.viewport(0, 0, target.width, target.height);
            gl.disable(glow::SCISSOR_TEST);
            gl.disable(glow::DEPTH_TEST);
            gl.clear_color(0.0, 0.0, 0.0, if backdrop == Backdrop::Clear { 0.0 } else { 1.0 });
            gl.clear(glow::COLOR_BUFFER_BIT);
            if backdrop != Backdrop::Clear {
                let program = &self.backdrop;
                gl.disable(glow::BLEND);
                gl.use_program(Some(program.program));
                gl.bind_vertex_array(Some(self.empty_array));
                let aspect = target.width.max(1) as f32 / target.height.max(1) as f32;
                let mut pictured = 0.0;
                if let (Backdrop::Picture { slide, dim }, Some((texture, [width, height]))) = (backdrop, self.picture) {
                    pictured = 1.0;
                    gl.active_texture(glow::TEXTURE0);
                    gl.bind_texture(glow::TEXTURE_2D, Some(texture));
                    gl.uniform_1_i32(program.location("picture"), 0);
                    gl.uniform_4_f32_slice(program.location("crop"), &crop(width as f32 / height.max(1) as f32, aspect, slide));
                    gl.uniform_1_f32(program.location("dim"), dim);
                }
                gl.uniform_1_f32(program.location("pictured"), pictured);
                gl.uniform_3_f32_slice(program.location("sky"), &NIGHT_SKY);
                gl.uniform_3_f32_slice(program.location("ground"), &NIGHT_GROUND);
                gl.uniform_2_f32(program.location("size"), target.width as f32 / target.scale, target.height as f32 / target.scale);
                gl.uniform_4_f32_slice(program.location("lamp"), &LAMP);
                gl.draw_arrays(glow::TRIANGLE_STRIP, 0, 4);
            }
            gl.viewport(target.x, target.y, target.w.max(1), target.h.max(1));
        }
    }

    /// `panel` is a rectangle something stands in front of, such as the keyboard, left, bottom, right, top in the
    /// simulation's coordinates: nothing of the snow shows inside it.
    #[allow(clippy::too_many_arguments)]
    pub fn draw(
        &mut self,
        target: &Target,
        simulation: &SnowSimulation,
        ambient: Ambient,
        panel: Option<[f32; 4]>,
        backdrop: Backdrop,
        time: f32,
        opacity: f32,
    ) -> Result<(), String> {
        let sprite_count = simulation.sprite_count();
        self.sprites.resize(sprite_count, Sprite::default());
        let sprites = simulation.write_sprites(&mut self.sprites);
        let columns = simulation.column_count();
        let rows = columns.div_ceil(ROW).max(1);
        self.columns.resize(rows * ROW, 0.0);
        let piles = simulation.write_layers(&mut self.columns[..columns]);
        let signs = simulation.signs();
        for sign in &signs {
            self.load_sign(sign.caption);
        }
        let pumpkins = simulation.pumpkins();
        if !pumpkins.is_empty() {
            self.load_pumpkin();
        }

        let gl = &self.gl;
        let scale = target.scale;
        let view = [simulation.width(), simulation.height()];
        let (light, shadow_tint) = halloween::moonlit(ambient.light, ambient.shadow_tint, ambient.night);
        let light = [light[0], light[1], light[2], 1.0];
        let rim = [1.0, 0.56, 0.2, 0.3 * ambient.night];
        let lantern = [ambient.lantern.x, ambient.lantern.y, LANTERN_REACH, ambient.night];
        // The pumpkins' candles light the snow as the finger's does, a little even by day; a missing one is dark.
        let mut candles = [[0.0; 4]; 3];
        for (candle, pumpkin) in candles.iter_mut().zip(&pumpkins) {
            let wick = wick(pumpkin);
            *candle = [wick.x, wick.y, pumpkin.width * 1.6, 0.4 + 0.6 * ambient.night];
        }
        let candles = candles.as_flattened();
        let shake = simulation.shake();
        let shake = [shake.x, shake.y];
        // An empty rectangle hides nothing. The piles, the signs and the pumpkins tell where they are in points; the
        // sprites and the field in the surface's own pixels.
        let panel = panel.unwrap_or([0.0; 4]);
        let panel_pixels = if panel == [0.0; 4] {
            panel
        } else {
            [
                target.x as f32 + panel[0] * scale,
                target.y as f32 + panel[1] * scale,
                target.x as f32 + panel[2] * scale,
                target.y as f32 + panel[3] * scale,
            ]
        };
        let autumn = if simulation.leafy() { 1.0 } else { 0.0 };
        let blossom = if simulation.blossom() { 1.0 } else { 0.0 };
        let wilt = if simulation.wilts() { 1.0 } else { 0.0 };
        unsafe {
            self.begin(target, backdrop);
            gl.enable(glow::BLEND);
            gl.blend_func_separate(glow::ONE, glow::ONE_MINUS_SRC_ALPHA, glow::ONE, glow::ONE_MINUS_SRC_ALPHA);

            // The pumpkins go down before the snow, so the drift they sit in covers their bottoms.
            if !pumpkins.is_empty()
                && let [Some(shell), Some(face)] = self.pumpkin_pictures
            {
                let pumpkin = &self.pumpkin;
                gl.use_program(Some(pumpkin.program));
                gl.bind_vertex_array(Some(self.empty_array));
                gl.active_texture(glow::TEXTURE1);
                gl.bind_texture(glow::TEXTURE_2D, Some(face));
                gl.active_texture(glow::TEXTURE0);
                gl.bind_texture(glow::TEXTURE_2D, Some(shell));
                gl.uniform_2_f32_slice(pumpkin.location("viewSize"), &view);
                gl.uniform_2_f32_slice(pumpkin.location("shake"), &shake);
                gl.uniform_1_f32(pumpkin.location("time"), time);
                gl.uniform_4_f32_slice(pumpkin.location("light"), &light);
                gl.uniform_1_f32(pumpkin.location("night"), ambient.night);
                gl.uniform_4_f32_slice(pumpkin.location("panel"), &panel);
                gl.uniform_1_i32(pumpkin.location("shell"), 0);
                gl.uniform_1_i32(pumpkin.location("face"), 1);
                for sitting in &pumpkins {
                    let wick = wick(sitting);
                    gl.uniform_2_f32(pumpkin.location("anchor"), sitting.anchor.x, sitting.anchor.y);
                    gl.uniform_2_f32(pumpkin.location("size"), sitting.width, sitting.width * PUMPKIN_ASPECT);
                    gl.uniform_2_f32(pumpkin.location("wick"), wick.x, wick.y);
                    gl.uniform_1_f32(pumpkin.location("seed"), sitting.seed);
                    gl.draw_arrays(glow::TRIANGLE_STRIP, 0, 4);
                }
            }
            if !piles.is_empty() {
                gl.active_texture(glow::TEXTURE0);
                gl.bind_texture(glow::TEXTURE_2D, Some(self.heights));
                let data = glow::PixelUnpackData::Slice(Some(bytes(&self.columns)));
                if rows == self.height_rows {
                    gl.tex_sub_image_2d(glow::TEXTURE_2D, 0, 0, 0, ROW as i32, rows as i32, glow::RED, glow::FLOAT, data);
                } else {
                    gl.tex_image_2d(glow::TEXTURE_2D, 0, glow::R32F as i32, ROW as i32, rows as i32, 0, glow::RED, glow::FLOAT, data);
                    self.height_rows = rows;
                }
                let pile = &self.pile;
                gl.use_program(Some(pile.program));
                gl.bind_vertex_array(Some(self.empty_array));
                gl.uniform_2_f32_slice(pile.location("viewSize"), &view);
                gl.uniform_1_f32(pile.location("scale"), scale);
                gl.uniform_1_f32(pile.location("time"), time);
                gl.uniform_1_f32(pile.location("columnWidth"), COLUMN_WIDTH);
                gl.uniform_4_f32_slice(pile.location("light"), &light);
                gl.uniform_4_f32_slice(pile.location("shadowTint"), &shadow_tint);
                gl.uniform_4_f32_slice(pile.location("rim"), &rim);
                gl.uniform_4_f32_slice(pile.location("lantern"), &lantern);
                gl.uniform_4_f32_slice(pile.location("pumpkins"), candles);
                gl.uniform_2_f32_slice(pile.location("shake"), &shake);
                gl.uniform_1_i32(pile.location("heights"), 0);
                gl.uniform_4_f32_slice(pile.location("panel"), &panel);
                gl.uniform_1_f32(pile.location("wilt"), wilt);
                for layer in &piles {
                    let count = layer.occluders.len().min(OCCLUDERS);
                    let occluders: Vec<f32> = layer.occluders[..count].iter().flatten().copied().collect();
                    gl.uniform_1_i32(pile.location("columnCount"), layer.count as i32);
                    gl.uniform_1_f32(pile.location("pileTop"), layer.top + 2.0);
                    gl.uniform_1_f32(pile.location("pileBottom"), layer.bottom);
                    gl.uniform_2_f32(pile.location("origin"), layer.origin.x, layer.origin.y);
                    gl.uniform_1_f32(pile.location("drift"), layer.drift);
                    gl.uniform_1_i32(pile.location("heightOffset"), layer.offset as i32);
                    gl.uniform_1_i32(pile.location("occluderCount"), count as i32);
                    if count > 0 {
                        gl.uniform_4_f32_slice(pile.location("occluders"), &occluders);
                    }
                    gl.draw_arrays(glow::TRIANGLE_STRIP, 0, 4);
                }
            }
            if !signs.is_empty() {
                let sign = &self.sign;
                gl.use_program(Some(sign.program));
                gl.bind_vertex_array(Some(self.empty_array));
                gl.active_texture(glow::TEXTURE0);
                gl.uniform_2_f32_slice(sign.location("viewSize"), &view);
                gl.uniform_2_f32_slice(sign.location("shake"), &shake);
                gl.uniform_1_f32(sign.location("time"), time);
                gl.uniform_4_f32_slice(sign.location("light"), &light);
                gl.uniform_4_f32_slice(sign.location("lantern"), &lantern);
                gl.uniform_4_f32_slice(sign.location("pumpkins"), candles);
                gl.uniform_4_f32_slice(sign.location("panel"), &panel);
                gl.uniform_1_i32(sign.location("picture"), 0);
                for held in &signs {
                    let Some(&Some((texture, size))) = self.signs.get(held.caption) else { continue };
                    gl.bind_texture(glow::TEXTURE_2D, Some(texture));
                    gl.uniform_2_f32(sign.location("anchor"), held.anchor.x, held.anchor.y);
                    gl.uniform_2_f32_slice(sign.location("size"), &size);
                    gl.uniform_1_f32(sign.location("angle"), held.angle);
                    gl.uniform_1_f32(sign.location("ground"), held.ground);
                    gl.draw_arrays(glow::TRIANGLE_STRIP, 0, 4);
                }
            }
            let size = (target.width.max(1), target.height.max(1));
            let glass = sprites > 0 && self.fields_drawable && simulation.has_glass_drops();
            if let Some((texture, framebuffer, made_for)) = self.field_target
                && (!glass || made_for != size)
            {
                gl.delete_framebuffer(framebuffer);
                gl.delete_texture(texture);
                self.field_target = None;
            }
            if glass && self.field_target.is_none() {
                let texture = gl.create_texture()?;
                gl.active_texture(glow::TEXTURE0);
                gl.bind_texture(glow::TEXTURE_2D, Some(texture));
                sampling(gl, glow::NEAREST, glow::NEAREST);
                gl.tex_image_2d(
                    glow::TEXTURE_2D,
                    0,
                    glow::RG16F as i32,
                    size.0,
                    size.1,
                    0,
                    glow::RG,
                    glow::HALF_FLOAT,
                    glow::PixelUnpackData::Slice(None),
                );
                let framebuffer = gl.create_framebuffer()?;
                gl.bind_framebuffer(glow::FRAMEBUFFER, Some(framebuffer));
                gl.framebuffer_texture_2d(glow::FRAMEBUFFER, glow::COLOR_ATTACHMENT0, glow::TEXTURE_2D, Some(texture), 0);
                let complete = gl.check_framebuffer_status(glow::FRAMEBUFFER) == glow::FRAMEBUFFER_COMPLETE;
                gl.bind_framebuffer(glow::FRAMEBUFFER, None);
                if complete {
                    self.field_target = Some((texture, framebuffer, size));
                } else {
                    // The driver said it could and can't: the drops are drawn as sprites from here on.
                    gl.delete_framebuffer(framebuffer);
                    gl.delete_texture(texture);
                    self.fields_drawable = false;
                }
            }
            if sprites > 0 {
                gl.bind_vertex_array(Some(self.sprite_array));
                gl.bind_buffer(glow::ARRAY_BUFFER, Some(self.sprite_buffer));
                gl.buffer_data_u8_slice(glow::ARRAY_BUFFER, bytes(&self.sprites[..sprites]), glow::STREAM_DRAW);
                // Drops on the glass first add up into their field.
                let field = if glass { self.field_target } else { None };
                if let Some((_, framebuffer, _)) = field {
                    let program = &self.field;
                    gl.bind_framebuffer(glow::FRAMEBUFFER, Some(framebuffer));
                    gl.viewport(0, 0, size.0, size.1);
                    gl.clear_color(0.0, 0.0, 0.0, 0.0);
                    gl.clear(glow::COLOR_BUFFER_BIT);
                    gl.viewport(target.x, target.y, target.w.max(1), target.h.max(1));
                    gl.blend_func(glow::ONE, glow::ONE);
                    gl.use_program(Some(program.program));
                    gl.uniform_2_f32_slice(program.location("viewSize"), &view);
                    gl.uniform_1_f32(program.location("scale"), scale);
                    gl.uniform_1_f32(program.location("time"), time);
                    gl.uniform_4_f32_slice(program.location("lantern"), &lantern);
                    gl.uniform_4_f32_slice(program.location("pumpkins"), candles);
                    gl.uniform_2_f32_slice(program.location("shake"), &shake);
                    gl.uniform_4_f32_slice(program.location("panel"), &panel_pixels);
                    gl.draw_arrays_instanced(glow::TRIANGLE_STRIP, 0, 4, sprites as i32);
                    gl.bind_framebuffer(glow::FRAMEBUFFER, None);
                    gl.blend_func_separate(glow::ONE, glow::ONE_MINUS_SRC_ALPHA, glow::ONE, glow::ONE_MINUS_SRC_ALPHA);
                }
                let sprite = &self.sprite;
                gl.use_program(Some(sprite.program));
                gl.uniform_1_f32(sprite.location("glass"), if field.is_some() { 1.0 } else { 0.0 });
                gl.uniform_2_f32_slice(sprite.location("viewSize"), &view);
                gl.uniform_1_f32(sprite.location("scale"), scale);
                gl.uniform_1_f32(sprite.location("time"), time);
                gl.uniform_4_f32_slice(sprite.location("light"), &light);
                gl.uniform_4_f32_slice(sprite.location("shadowTint"), &shadow_tint);
                gl.uniform_4_f32_slice(sprite.location("rim"), &rim);
                gl.uniform_4_f32_slice(sprite.location("lantern"), &lantern);
                gl.uniform_4_f32_slice(sprite.location("pumpkins"), candles);
                gl.uniform_2_f32_slice(sprite.location("shake"), &shake);
                gl.uniform_4_f32_slice(sprite.location("panel"), &panel_pixels);
                gl.uniform_1_f32(sprite.location("autumn"), autumn);
                gl.uniform_1_f32(sprite.location("blossom"), blossom);
                gl.uniform_1_f32(sprite.location("wilt"), wilt);
                gl.draw_arrays_instanced(glow::TRIANGLE_STRIP, 0, 4, sprites as i32);
                if let Some((texture, _, _)) = field {
                    let program = &self.glass;
                    gl.bind_vertex_array(Some(self.empty_array));
                    gl.use_program(Some(program.program));
                    gl.active_texture(glow::TEXTURE0);
                    gl.bind_texture(glow::TEXTURE_2D, Some(texture));
                    gl.uniform_1_i32(program.location("field"), 0);
                    gl.uniform_4_f32_slice(program.location("light"), &light);
                    gl.uniform_1_f32(program.location("scale"), scale);
                    gl.draw_arrays(glow::TRIANGLES, 0, 3);
                }
            }
            // Fading under a full-screen app or back: the snow as drawn, all of it at once, so where flakes cross a
            // drift it fades as one. Only a see-through surface fades: a wallpaper keeps its backdrop.
            if opacity < 1.0 && backdrop == Backdrop::Clear {
                gl.bind_vertex_array(Some(self.empty_array));
                gl.use_program(Some(self.fade.program));
                gl.blend_color(0.0, 0.0, 0.0, opacity);
                gl.blend_func_separate(glow::ZERO, glow::CONSTANT_ALPHA, glow::ZERO, glow::CONSTANT_ALPHA);
                gl.draw_arrays(glow::TRIANGLE_STRIP, 0, 4);
            }
            gl.bind_vertex_array(None);
        }
        Ok(())
    }

    /// The surface as drawn, rows from the bottom, red, green, blue and alpha: for looking at it off a phone.
    pub fn read(&self, target: &Target) -> Vec<u8> {
        let mut pixels = vec![0u8; target.width.max(1) as usize * target.height.max(1) as usize * 4];
        unsafe {
            self.gl.bind_framebuffer(glow::FRAMEBUFFER, None);
            self.gl.read_pixels(
                0,
                0,
                target.width.max(1),
                target.height.max(1),
                glow::RGBA,
                glow::UNSIGNED_BYTE,
                glow::PixelPackData::Slice(Some(&mut pixels)),
            );
        }
        pixels
    }

    /// Makes a caption's sign the first time a ghost holds it up.
    fn load_sign(&mut self, caption: usize) {
        if self.signs.get(caption).is_none_or(Option::is_some) {
            return;
        }
        let Some((texture, [width, height])) = self.picture(SIGNS[caption], false) else { return };
        self.signs[caption] = Some((texture, [width as f32 / 2.0, height as f32 / 2.0]));
    }

    /// Makes the pumpkin's pictures the first time one is drawn. They are mipmapped: the pumpkins are shown at a
    /// fraction of their size, and would shimmer otherwise.
    fn load_pumpkin(&mut self) {
        for (part, png) in PUMPKIN.iter().enumerate() {
            if self.pumpkin_pictures[part].is_none() {
                self.pumpkin_pictures[part] = self.picture(png, true).map(|(texture, _)| texture);
            }
        }
    }

    /// A PNG as a texture, with its colours premultiplied as the blending wants, and its size in pixels.
    fn picture(&self, png: &[u8], mipmapped: bool) -> Option<(glow::Texture, [u32; 2])> {
        let (mut pixels, width, height) = decode(png)?;
        for pixel in pixels.chunks_exact_mut(4) {
            let alpha = pixel[3] as u32;
            for channel in &mut pixel[..3] {
                *channel = ((*channel as u32 * alpha + 127) / 255) as u8;
            }
        }
        let gl = &self.gl;
        unsafe {
            let texture = gl.create_texture().ok()?;
            gl.active_texture(glow::TEXTURE0);
            gl.bind_texture(glow::TEXTURE_2D, Some(texture));
            sampling(gl, if mipmapped { glow::LINEAR_MIPMAP_LINEAR } else { glow::LINEAR }, glow::LINEAR);
            gl.tex_image_2d(
                glow::TEXTURE_2D,
                0,
                glow::RGBA8 as i32,
                width as i32,
                height as i32,
                0,
                glow::RGBA,
                glow::UNSIGNED_BYTE,
                glow::PixelUnpackData::Slice(Some(&pixels)),
            );
            if mipmapped {
                gl.generate_mipmap(glow::TEXTURE_2D);
            }
            Some((texture, [width, height]))
        }
    }
}

/// A sprite's size, alpha, softness and tint amount, as the Linux shader takes them: an attribute each.
const LONE: &str = "layout(location = 2) in float size;
layout(location = 3) in float alpha;
layout(location = 4) in float soft;
layout(location = 5) in float tintAmount;
";
/// The same four, one after another in a `Sprite`, as one attribute, the way the Windows renderer takes them.
const PACKED: &str = "layout(location = 2) in vec4 body;
#define size body.x
#define alpha body.y
#define soft body.z
#define tintAmount body.w
";

/// The sprite's vertex shader taking sixteen numbers' worth of attributes as thirteen. A phone's graphics driver need
/// have no more than sixteen attributes, and some count the vertex's own index among them: with sixteen of the
/// shader's own the program then fails to link, and nothing of the snow is drawn.
pub fn packed(vertex: &str) -> String {
    vertex.replace(LONE, PACKED)
}

/// The part of a picture `picture` wide for its height that fills a surface `surface` wide for its height: where it
/// starts and how far it reaches, across and down, in shares of the picture. A picture wider than the surface is seen
/// `slide` of the way along, from 0 to 1.
pub fn crop(picture: f32, surface: f32, slide: f32) -> [f32; 4] {
    if picture > surface {
        let across = surface / picture;
        [(1.0 - across) * slide.clamp(0.0, 1.0), 0.0, across, 1.0]
    } else {
        let down = picture / surface;
        [0.0, (1.0 - down) * 0.5, 1.0, down]
    }
}

/// A PNG's pixels as red, green, blue and alpha bytes, rows from the top, with its size.
pub fn decode(png: &[u8]) -> Option<(Vec<u8>, u32, u32)> {
    let mut decoder = png::Decoder::new(png);
    decoder.set_transformations(png::Transformations::normalize_to_color8());
    let mut reader = decoder.read_info().ok()?;
    let mut pixels = vec![0; reader.output_buffer_size()];
    let info = reader.next_frame(&mut pixels).ok()?;
    pixels.truncate(info.buffer_size());
    let pixels = match info.color_type {
        png::ColorType::Rgba => pixels,
        png::ColorType::Rgb => pixels.chunks_exact(3).flat_map(|p| [p[0], p[1], p[2], 255]).collect(),
        png::ColorType::GrayscaleAlpha => pixels.chunks_exact(2).flat_map(|p| [p[0], p[0], p[0], p[1]]).collect(),
        png::ColorType::Grayscale => pixels.iter().flat_map(|&p| [p, p, p, 255]).collect(),
        png::ColorType::Indexed => return None,
    };
    Some((pixels, info.width, info.height))
}

/// How the texture bound now is read between its pixels; never past its edges.
unsafe fn sampling(gl: &glow::Context, smaller: u32, larger: u32) {
    unsafe {
        for (parameter, value) in [
            (glow::TEXTURE_MIN_FILTER, smaller),
            (glow::TEXTURE_MAG_FILTER, larger),
            (glow::TEXTURE_WRAP_S, glow::CLAMP_TO_EDGE),
            (glow::TEXTURE_WRAP_T, glow::CLAMP_TO_EDGE),
        ] {
            gl.tex_parameter_i32(glow::TEXTURE_2D, parameter, value as i32);
        }
    }
}

/// Where a pumpkin's candle burns, in the simulation's points.
fn wick(pumpkin: &PumpkinSprite) -> V2 {
    let [across, up] = PUMPKIN_CANDLE;
    pumpkin.anchor + V2::new((across - 0.5) * pumpkin.width, up * pumpkin.width * PUMPKIN_ASPECT)
}

/// Plain numbers as the bytes OpenGL takes.
fn bytes<T: Copy>(values: &[T]) -> &[u8] {
    unsafe { std::slice::from_raw_parts(values.as_ptr().cast::<u8>(), std::mem::size_of_val(values)) }
}

unsafe fn program(gl: &glow::Context, vertex: &str, fragment: &str, uniforms: &[&str]) -> Result<Program, String> {
    unsafe {
        let program = gl.create_program()?;
        let mut shaders = Vec::new();
        for (kind, source) in [(glow::VERTEX_SHADER, vertex), (glow::FRAGMENT_SHADER, fragment)] {
            let shader = gl.create_shader(kind)?;
            gl.shader_source(shader, source);
            gl.compile_shader(shader);
            if !gl.get_shader_compile_status(shader) {
                return Err(format!("shader: {}", gl.get_shader_info_log(shader)));
            }
            gl.attach_shader(program, shader);
            shaders.push(shader);
        }
        gl.link_program(program);
        if !gl.get_program_link_status(program) {
            return Err(format!("program: {}", gl.get_program_info_log(program)));
        }
        for shader in shaders {
            gl.detach_shader(program, shader);
            gl.delete_shader(shader);
        }
        let uniforms = uniforms.iter().map(|&name| (name.to_string(), gl.get_uniform_location(program, name))).collect();
        Ok(Program { program, uniforms })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_wide_picture_is_cropped_at_its_sides_and_slides() {
        let [x, y, w, h] = crop(2.0, 0.5, 0.5);
        assert!((w - 0.25).abs() < 1e-6 && h == 1.0 && y == 0.0);
        assert!((x - 0.375).abs() < 1e-6);
        assert_eq!(crop(2.0, 0.5, 0.0)[0], 0.0);
    }

    #[test]
    fn a_tall_picture_is_cropped_top_and_bottom() {
        let [x, y, w, h] = crop(0.25, 0.5, 0.3);
        assert!(x == 0.0 && w == 1.0 && (h - 0.5).abs() < 1e-6 && (y - 0.25).abs() < 1e-6);
    }

    #[test]
    fn the_sprite_shader_leaves_room_among_the_attributes() {
        assert_eq!(SPRITE_VERTEX.matches("layout(location").count(), 16);
        let vertex = packed(SPRITE_VERTEX);
        assert!(vertex.contains("in vec4 body;") && !vertex.contains("in float size;"));
        // Sixteen is all a driver must have, the vertex's index may be one of them, and two are to spare.
        assert_eq!(vertex.matches("layout(location").count(), 13);
        for taken in ["size", "alpha", "soft", "tintAmount"] {
            // Nothing else in the shader goes by a name the four are now only other words for.
            assert!(!vertex.contains(&format!(".{taken}")) && !vertex.contains(&format!("float {taken} ")), "{taken}");
        }
    }

    #[test]
    fn the_signs_and_the_pumpkin_decode() {
        for png in SIGNS.iter().chain(PUMPKIN.iter()) {
            let (pixels, width, height) = decode(png).expect("a picture");
            assert_eq!(pixels.len(), width as usize * height as usize * 4);
        }
    }
}
