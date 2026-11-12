//! Draws the snow as a phone would show it, into PNGs, without a phone: `cargo run --release --features host
//! --example shots -- <folder>`. Each picture is a scene stepped for a while at the pace the app draws it.

use snowhush::host::{Offscreen, save};
use snowhush::render::Backdrop;
use snowhush::scene::{Reported, Scene};
use snowhush_core::simulation::Mode;

/// A phone 360 by 780 points at three pixels to the point.
const WIDTH: i32 = 1080;
const HEIGHT: i32 = 2340;
const SCALE: f32 = 3.0;
/// A wallpaper behind the see-through surface: dusk, top and bottom.
const BEHIND: ([u8; 3], [u8; 3]) = ([52, 64, 92], [22, 24, 34]);

struct Shot {
    name: &'static str,
    modes: &'static [Mode],
    seconds: f32,
    intensity: f32,
    halloween: bool,
    /// What a navigation bar with buttons takes of the bottom, in pixels.
    bar: i32,
    windows: &'static [Reported],
    backdrop: Backdrop,
    melt: bool,
}

const KEYBOARD: Reported =
    Reported { id: 1, left: 0.0, top: 1500.0, right: 1080.0, bottom: 2340.0, radius: 0.0, settles: true, panel: true, away: false };
const PICTURE_IN_PICTURE: Reported =
    Reported { id: 2, left: 480.0, top: 620.0, right: 1032.0, bottom: 932.0, radius: 48.0, settles: true, panel: false, away: false };
const CARD: Reported =
    Reported { id: 3, left: 48.0, top: 1100.0, right: 1032.0, bottom: 1700.0, radius: 36.0, settles: true, panel: false, away: false };

fn main() -> Result<(), String> {
    let folder = std::env::args().nth(1).unwrap_or_else(|| "shots".into());
    std::fs::create_dir_all(&folder).map_err(|error| error.to_string())?;
    let _context = Offscreen::new(WIDTH, HEIGHT)?;
    let base = Shot {
        name: "",
        modes: &[Mode::Snow],
        seconds: 240.0,
        intensity: 0.75,
        halloween: false,
        bar: 0,
        windows: &[],
        backdrop: Backdrop::Clear,
        melt: false,
    };
    let shots = [
        Shot { name: "snow", windows: &[PICTURE_IN_PICTURE], ..base },
        Shot { name: "snow-keyboard", windows: &[KEYBOARD, PICTURE_IN_PICTURE], bar: 144, melt: true, ..base },
        Shot { name: "snow-cards", windows: &[CARD], bar: 144, ..base },
        Shot { name: "autumn", modes: &[Mode::Autumn], windows: &[CARD], ..base },
        Shot { name: "rain", modes: &[Mode::Rain], seconds: 40.0, windows: &[CARD, PICTURE_IN_PICTURE], ..base },
        Shot { name: "blossom", modes: &[Mode::Blossom], windows: &[CARD], ..base },
        Shot { name: "mixed", modes: &[Mode::Snow, Mode::Autumn], windows: &[CARD], ..base },
        Shot { name: "halloween", halloween: true, seconds: 300.0, ..base },
        Shot { name: "wallpaper-night", backdrop: Backdrop::Night, intensity: 0.5, ..base },
    ];
    for shot in shots {
        let mut scene = Scene::new();
        scene.set_intensity(shot.intensity);
        scene.set_modes(shot.modes, false, false);
        scene.set_melt(shot.melt);
        scene.set_halloween(shot.halloween);
        scene.set_hour(if shot.halloween { 22.0 } else { 15.0 });
        scene.set_backdrop(shot.backdrop);
        scene.place(WIDTH, HEIGHT, 0, 0, shot.bar, SCALE);
        scene.gl_init()?;
        scene.set_windows(shot.windows.to_vec(), false);
        // The first frame hands the simulation its windows. Drawing in software is slow, so the snow is then stepped
        // on undrawn, at the pace the app draws it, and only the last moments are drawn as the app would.
        let mut now = 0i64;
        scene.frame(now);
        const STEP: f32 = 1.0 / 30.0;
        if let Some(simulation) = scene.simulation_mut() {
            for _ in 0..(shot.seconds / STEP) as usize {
                simulation.step(STEP, snowhush_core::geometry::V2::new(-1e6, -1e6));
            }
        }
        let mut draws = 0;
        for _ in 0..4 {
            now += 33_333_333;
            scene.resume();
            draws += scene.frame(now).drew as u32;
        }
        let path = format!("{folder}/{}.png", shot.name);
        save(&scene, &path, (shot.backdrop == Backdrop::Clear).then_some(BEHIND))?;
        println!("{path}: {draws} frames drawn; {}", scene.describe());
    }
    Ok(())
}
