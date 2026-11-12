//! The snow on one surface: its simulation, what Android told about the screen it is on, and the pace it is drawn at,
//! as `linux/src/screen.rs` and `linux/src/snow.rs` keep it for a display. The surface is the overlay over every app, a
//! live wallpaper's, or the one over the app's own settings; each has a scene of its own, on a thread of its own.
//!
//! Android measures in pixels from the top left; the simulation in points from the bottom left of the part of the
//! surface the snow has. A point is one density-independent pixel, so the snow is as big in the hand as on a desk.

use std::collections::HashSet;

use snowhush_core::geometry::{Rect, V2};
use snowhush_core::halloween;
use snowhush_core::simulation::{Mode, Pace, SnowSimulation, WindowId, WindowSurface};

use crate::light;
use crate::render::{Ambient, Backdrop, Gpu, LANTERN_REACH, Target};

/// How much the snow may spend on drawing.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PowerBudget {
    /// Charging.
    Full,
    /// On battery, as a phone mostly is: no frame rates above 60, which are hard to tell apart in snow anyway.
    Battery,
    /// Battery saver: a little less snow, drawn a little less often.
    Saver,
}

/// How often a surface with nothing on it and nothing coming looks again, besides being woken.
const RESTING: i64 = 1_000_000_000;
/// How often a surface faded out under a full-screen app looks whether it may come back, besides being woken.
const HIDDEN: i64 = 250_000_000;
/// Seconds the snow takes to fade out of a full-screen app's way, and back in once it goes.
const FADE_OUT: f32 = 0.25;
const FADE_IN: f32 = 0.35;
/// The simulation's pointer is a mouse's arrow, whose tip is up and left of where it pushes the snow; a fingertip
/// pushes where it is. See `CURSOR_OFFSET` in the core.
const FINGER: V2 = V2::new(-4.0, 8.0);
const AWAY: V2 = V2::new(-1e6, -1e6);
/// The air a sliding desktop drags along is this share of its speed, in the core; the wind of a tilted phone is
/// handed to it as the slide that stirs up as much.
const SLIDE_DRAG: f32 = 0.025;
/// How fast the phone must be tilted before its wind is worth a frame of its own.
const WIND_FELT: f32 = 2.0;

/// What the app's settings say.
#[derive(Clone, Debug, PartialEq)]
pub struct Settings {
    /// How much falls, 0 to 1.
    pub intensity: f32,
    /// How opaque what falls is drawn, 0.1 to 1.
    pub opacity: f32,
    /// Snow and leaves settle along the bottom of the screen.
    pub ground: bool,
    /// Snow and leaves settle on the keyboard and on windows.
    pub ledges: bool,
    pub melt: bool,
    /// What falls: one kind, or two mixed.
    pub modes: Vec<Mode>,
    /// Halloween is wanted today; it only shows over snow falling alone.
    pub halloween: bool,
}

impl Default for Settings {
    fn default() -> Settings {
        Settings { intensity: 0.5, opacity: 1.0, ground: true, ledges: true, melt: true, modes: vec![Mode::Snow], halloween: false }
    }
}

/// A window as Android reported it, in the surface's pixels from its top left. They come front to back.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Reported {
    pub id: WindowId,
    pub left: f32,
    pub top: f32,
    pub right: f32,
    pub bottom: f32,
    /// How round its top corners are, in pixels.
    pub radius: f32,
    /// Snow can lie along its top edge.
    pub settles: bool,
    /// It stands in front of the snow, as the keyboard does: nothing of the snow shows inside it.
    pub panel: bool,
    /// It is out of sight for now and will be back, as a card scrolled away: its snow waits for it.
    pub away: bool,
}

impl Reported {
    /// How many numbers tell of one window: its id, left, top, right, bottom, radius and what it is, with one to spare.
    pub const NUMBERS: usize = 8;
    const SETTLES: u32 = 1;
    const PANEL: u32 = 2;
    const AWAY: u32 = 4;

    pub fn read(numbers: &[f32]) -> Reported {
        let kind = numbers[6] as u32;
        Reported {
            id: numbers[0] as WindowId,
            left: numbers[1],
            top: numbers[2],
            right: numbers[3],
            bottom: numbers[4],
            radius: numbers[5],
            settles: kind & Reported::SETTLES != 0,
            panel: kind & Reported::PANEL != 0,
            away: kind & Reported::AWAY != 0,
        }
    }
}

/// The reported windows as the simulation wants them.
#[derive(Default, PartialEq)]
pub struct Placed {
    /// The windows snow can settle on.
    pub surfaces: Vec<WindowSurface>,
    /// The rectangle nothing of the snow shows in, left, bottom, right, top in points.
    pub panel: Option<[f32; 4]>,
    /// The window standing in front of the snow along the bottom, which pumpkins over it sit on.
    pub shelf: Option<WindowId>,
    /// The windows out of sight whose snow waits for them.
    pub away: HashSet<WindowId>,
}

/// Turns what Android reported into the windows of a simulation `width` by `height` points, as `windows::surfaces`
/// does on Linux: a window collects snow when it has room above it, doesn't fill the screen and is big enough, and
/// those in front of it hide what lies behind them. Without `ledges`, none collects snow.
pub fn place(reported: &[Reported], target: &Target, width: f32, height: f32, ledges: bool) -> Placed {
    let scale = target.scale;
    let mut placed = Placed::default();
    let mut blockers: Vec<Rect> = Vec::new();
    for window in reported {
        if window.away {
            placed.away.insert(window.id);
            continue;
        }
        let frame = Rect {
            x: (window.left - target.x as f32) / scale,
            y: (target.height as f32 - window.bottom - target.y as f32) / scale,
            width: (window.right - window.left) / scale,
            height: (window.bottom - window.top) / scale,
        };
        if frame.width <= 0.0 || frame.height <= 0.0 {
            continue;
        }
        let radius = (window.radius / scale).min(frame.width / 2.0).min(frame.height / 2.0).max(0.0);
        if window.panel && placed.panel.is_none() {
            // Below the rounded corners only, so snow curling round them stays in front.
            placed.panel = Some([frame.min_x(), frame.min_y(), frame.max_x(), frame.max_y() - radius]);
        }
        let room_above = height - frame.max_y() >= 6.0;
        let on_screen = frame.max_x() > 0.0 && frame.min_x() < width && frame.max_y() > 0.0;
        let fills_screen = frame.width * frame.height >= width * height * 0.9;
        if ledges && window.settles && room_above && on_screen && !fills_screen && frame.width >= 80.0 && frame.height >= 40.0 {
            let region = Rect { x: frame.x, y: frame.max_y() - radius, width: frame.width, height: 40.0 + radius };
            placed.surfaces.push(WindowSurface {
                id: window.id,
                frame,
                blockers: blockers.iter().filter(|b| intersects(b, &region)).copied().collect(),
                cover: blockers.iter().filter(|b| intersects(b, &frame)).copied().collect(),
                corner_radius: radius,
                corner_exponent: 2.0,
            });
            if window.panel && placed.shelf.is_none() {
                placed.shelf = Some(window.id);
            }
        }
        blockers.push(frame);
    }
    placed
}

fn intersects(a: &Rect, b: &Rect) -> bool {
    a.min_x() < b.max_x() && b.min_x() < a.max_x() && a.min_y() < b.max_y() && b.min_y() < a.max_y()
}

/// What a frame came to.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Frame {
    /// Something was drawn: the surface wants showing.
    pub drew: bool,
    /// Nanoseconds until the next frame is due.
    pub wait: i64,
}

pub struct Scene {
    simulation: Option<SnowSimulation>,
    gpu: Option<Gpu>,
    target: Option<Target>,
    settings: Settings,
    budget: PowerBudget,
    /// The fastest the snow is drawn, a second: the display's own rate, between 60 and 120.
    interactive_rate: u32,
    /// Every frame at `interactive_rate`, whatever the snow is doing and whatever the power.
    max_rate: bool,
    /// The hour of the day, which the light follows.
    hour: f32,
    shadow_tint: [f32; 4],
    backdrop: Backdrop,
    /// The finger, in the surface's pixels from its top left, while it is down.
    pointer: Option<(f32, f32)>,
    /// The wind a tilted phone blows, in points a second, to the right when positive.
    wind: f32,
    reported: Vec<Reported>,
    reported_moving: bool,
    /// The windows, the part of the surface the snow has or what it settles on changed: the simulation hears of it at
    /// the next frame.
    windows_stale: bool,
    windows_changed_at: i64,
    surfaces: Vec<WindowSurface>,
    panel: Option<[f32; 4]>,
    covered: bool,
    /// How much of the snow shows, from 0 to 1.
    opacity: f32,
    started: Option<i64>,
    last_step: Option<i64>,
    idle_frames: u32,
    /// Where the finger's candle was last drawn.
    lantern: V2,
    /// Whether the last frame drawn showed Halloween's pumpkins.
    pumpkins_shown: bool,
    /// Something outside the simulation changed what the surface should show: the next frame is drawn come what may.
    stirred: bool,
    frames: u64,
    draws: u64,
}

impl Default for Scene {
    fn default() -> Scene {
        Scene::new()
    }
}

impl Scene {
    pub fn new() -> Scene {
        Scene {
            simulation: None,
            gpu: None,
            target: None,
            settings: Settings::default(),
            budget: PowerBudget::Battery,
            interactive_rate: 60,
            max_rate: false,
            hour: 12.0,
            shadow_tint: [0.0; 4],
            backdrop: Backdrop::Clear,
            pointer: None,
            wind: 0.0,
            reported: Vec::new(),
            reported_moving: false,
            windows_stale: true,
            windows_changed_at: 0,
            surfaces: Vec::new(),
            panel: None,
            covered: false,
            opacity: 1.0,
            started: None,
            last_step: None,
            idle_frames: 0,
            lantern: AWAY,
            pumpkins_shown: false,
            stirred: true,
            frames: 0,
            draws: 0,
        }
    }

    /// Makes what the snow is drawn with, in the context current on this thread.
    pub fn gl_init(&mut self) -> Result<(), String> {
        self.gpu = None;
        self.gpu = Some(Gpu::new()?);
        self.resume();
        Ok(())
    }

    /// The context is gone, or about to go, and everything made in it with it.
    pub fn gl_drop(&mut self) {
        self.gpu = None;
    }

    pub fn simulation(&self) -> Option<&SnowSimulation> {
        self.simulation.as_ref()
    }

    pub fn simulation_mut(&mut self) -> Option<&mut SnowSimulation> {
        self.simulation.as_mut()
    }

    pub fn gpu(&self) -> Option<&Gpu> {
        self.gpu.as_ref()
    }

    pub fn target(&self) -> Option<Target> {
        self.target
    }

    pub fn settings(&self) -> &Settings {
        &self.settings
    }

    /// The surface is `width` by `height` pixels, `scale` of them to a point, and the snow has all of it but what a
    /// navigation bar with buttons takes along its `left`, `right` or `bottom` edge: the snow lies on top of the bar,
    /// as on a taskbar. A bar that comes or goes along the bottom takes the drift up with it, or lets it fall.
    pub fn place(&mut self, width: i32, height: i32, left: i32, right: i32, bottom: i32, scale: f32) {
        let scale = if scale.is_finite() && scale > 0.0 { scale } else { 1.0 };
        let (width, height) = (width.max(1), height.max(1));
        let (left, right, bottom) = (left.clamp(0, width - 1), right.clamp(0, width - 1), bottom.clamp(0, height - 1));
        let target = Target { width, height, x: left, y: bottom, w: (width - left - right).max(1), h: (height - bottom).max(1), scale };
        if self.target == Some(target) {
            return;
        }
        let (w, h) = (target.w as f32 / scale, target.h as f32 / scale);
        match (&mut self.simulation, self.target) {
            (Some(simulation), Some(old))
                if old.scale == scale && old.width == width && old.height == height && old.x == left && old.w == target.w =>
            {
                // Only the bottom moved, by this much, down when positive.
                let by = (old.y - target.y) as f32 / scale;
                if by > 0.0 { simulation.lower_ground(w, h, by) } else { simulation.raise_ground(w, h, -by) }
            }
            (Some(simulation), _) => simulation.resize(w, h),
            (None, _) => self.simulation = Some(new_simulation(w, h, &self.settings, self.budget)),
        }
        self.target = Some(target);
        self.windows_stale = true;
        self.resume();
    }

    pub fn set_intensity(&mut self, intensity: f32) {
        self.settings.intensity = intensity.clamp(0.0, 1.0);
        if let Some(simulation) = &mut self.simulation {
            simulation.set_intensity(self.settings.intensity);
        }
        self.stirred = true;
    }

    pub fn set_opacity(&mut self, opacity: f32) {
        self.settings.opacity = opacity.clamp(0.1, 1.0);
        if let Some(simulation) = &mut self.simulation {
            simulation.falling_opacity = self.settings.opacity;
        }
        self.stirred = true;
    }

    /// What the snow settles on: the bottom of the screen, and the keyboard and windows. Windows no longer settled on
    /// let their snow fall.
    pub fn set_surfaces(&mut self, ground: bool, ledges: bool) {
        self.settings.ground = ground;
        self.settings.ledges = ledges;
        if let Some(simulation) = &mut self.simulation {
            simulation.ground_settles = ground;
            simulation.accumulates = ground || ledges;
        }
        self.windows_stale = true;
        self.stirred = true;
    }

    pub fn set_melt(&mut self, melt: bool) {
        self.settings.melt = melt;
        if let Some(simulation) = &mut self.simulation {
            simulation.thaws = melt;
        }
    }

    /// Other kinds fall. Picked from the settings, `switch`, what lies is cleared away first, with a gust or, `calm`,
    /// by melting where it lies, and the new kinds come from the top; otherwise they fall at once.
    pub fn set_modes(&mut self, modes: &[Mode], switch: bool, calm: bool) {
        self.settings.modes = if modes.is_empty() { vec![Mode::Snow] } else { modes.to_vec() };
        if let Some(simulation) = &mut self.simulation {
            if switch {
                let direction = simulation.breeze_direction();
                simulation.switch_modes(&self.settings.modes, direction, calm);
            } else {
                simulation.set_modes(&self.settings.modes);
            }
        }
        self.haunt();
        self.stirred = true;
    }

    /// Halloween is wanted today, or no longer.
    pub fn set_halloween(&mut self, wanted: bool) {
        self.settings.halloween = wanted;
        self.haunt();
        self.stirred = true;
    }

    /// Whether the snow has Halloween's ghosts: wanted, over snow, not leaves.
    pub fn halloween(&self) -> bool {
        self.settings.halloween && self.settings.modes == [Mode::Snow]
    }

    fn haunt(&mut self) {
        let on = self.halloween();
        if let Some(simulation) = &mut self.simulation {
            simulation.set_halloween(on);
        }
    }

    pub fn set_power(&mut self, budget: PowerBudget) {
        if budget == self.budget {
            return;
        }
        self.budget = budget;
        if let Some(simulation) = &mut self.simulation {
            simulation.set_density(density(budget));
        }
    }

    /// The display's refresh rate, and whether every frame is drawn at it.
    pub fn set_rate(&mut self, refresh: f32, max_rate: bool) {
        self.interactive_rate = if refresh.is_finite() { (refresh.round() as i64).clamp(60, 120) as u32 } else { 60 };
        self.max_rate = max_rate;
    }

    pub fn set_hour(&mut self, hour: f32) {
        self.hour = if hour.is_finite() { hour.rem_euclid(24.0) } else { 12.0 };
        self.stirred = true;
    }

    /// A colour of the wallpaper, which the shadows take on, or none.
    pub fn set_shadow(&mut self, color: Option<[f32; 3]>) {
        self.shadow_tint = color.map_or([0.0; 4], light::tint);
        self.stirred = true;
    }

    pub fn set_backdrop(&mut self, backdrop: Backdrop) {
        if backdrop != self.backdrop {
            self.backdrop = backdrop;
            self.stirred = true;
        }
    }

    /// A wallpaper's picture, as rows of red, green, blue and alpha bytes from the top, or none to take it away. It
    /// lives in the graphics context: one that is made anew wants it again.
    pub fn set_picture(&mut self, picture: Option<(&[u8], u32, u32)>) {
        if let Some(gpu) = &mut self.gpu {
            gpu.set_picture(picture);
            self.stirred = true;
        }
    }

    /// The finger, in the surface's pixels from its top left, or none once it is lifted.
    pub fn set_pointer(&mut self, pointer: Option<(f32, f32)>) {
        self.pointer = pointer.filter(|(x, y)| x.is_finite() && y.is_finite());
    }

    /// The wind a tilted phone blows, in points a second, to the right when positive.
    pub fn set_wind(&mut self, wind: f32) {
        self.wind = if wind.is_finite() { wind } else { 0.0 };
    }

    /// The home screen's pages slid past at this many pixels a second, negative to the left: the air they drag along
    /// blows the falling snow that way for a moment.
    pub fn slide(&mut self, velocity: f32) {
        if let (Some(simulation), Some(target)) = (&mut self.simulation, self.target)
            && velocity.is_finite()
        {
            simulation.space_slid(velocity / target.scale);
        }
    }

    /// Blows all the snow away, to the right when `direction` is positive, to the left when negative, and the way
    /// the breeze blows when zero; when `calm`, for less motion, melts it where it lies instead.
    pub fn clear(&mut self, calm: bool, direction: f32) {
        if let Some(simulation) = &mut self.simulation {
            let direction = if direction == 0.0 { simulation.breeze_direction() } else { direction };
            simulation.blow_away(direction, calm);
        }
        self.stirred = true;
    }

    /// The windows on the screen, front to back; `moving` while one is being dragged or scrolled, so its snow is
    /// carried along instead of being left behind.
    pub fn set_windows(&mut self, reported: Vec<Reported>, moving: bool) {
        if reported == self.reported && moving == self.reported_moving {
            return;
        }
        self.reported = reported;
        self.reported_moving = moving;
        self.windows_stale = true;
    }

    /// Fades the snow out while a full-screen app covers the screen, then stops it; once the app goes, the snow fades
    /// back in from where it stopped.
    pub fn set_covered(&mut self, covered: bool) {
        if covered == self.covered {
            return;
        }
        self.covered = covered;
        self.resume();
    }

    /// Faded all the way out under a full-screen app: nothing to draw until it goes.
    pub fn hidden(&self) -> bool {
        self.covered && self.opacity <= 0.0
    }

    /// Goes on at the next frame from where the snow is, without making up for the time away, as after the screen was
    /// off.
    pub fn resume(&mut self) {
        self.last_step = None;
        self.stirred = true;
    }

    /// Steps the snow to `now`, in nanoseconds on a clock that never goes back, and draws it if anything visible
    /// changed, into the context current on this thread.
    pub fn frame(&mut self, now: i64) -> Frame {
        if self.simulation.is_none() || self.gpu.is_none() {
            return Frame { drew: false, wait: RESTING };
        }
        let Some(target) = self.target else { return Frame { drew: false, wait: RESTING } };
        let stirred = std::mem::take(&mut self.stirred);
        if self.hidden() {
            self.last_step = None;
            if stirred && let Some(gpu) = &self.gpu {
                gpu.clear(&target, self.backdrop);
            }
            return Frame { drew: stirred, wait: HIDDEN };
        }
        let started = *self.started.get_or_insert(now);
        let time = ((now - started).max(0) as f64 / 1e9 % 1000.0) as f32;
        let dt = self.last_step.map_or(1.0 / 60.0, |last| ((now - last).max(0) as f32 / 1e9).clamp(1.0 / 1000.0, 0.05));
        self.last_step = Some(now);
        self.frames += 1;
        self.follow(now);

        let pointer = self.pointer.map_or(AWAY, |(x, y)| local(&target, x, y));
        let (Some(simulation), Some(gpu)) = (&mut self.simulation, &mut self.gpu) else { return Frame { drew: false, wait: RESTING } };
        if self.wind.abs() >= WIND_FELT {
            simulation.space_slid(self.wind / SLIDE_DRAG);
        }
        let cursor = if pointer == AWAY { AWAY } else { pointer + FINGER };
        let changed = simulation.step(dt, cursor);
        // Nothing to draw and nothing on its way: the frame shown last stays up, as it would, till the snow is stirred.
        if !changed && !self.pumpkins_shown && simulation.is_empty() {
            self.opacity = if self.covered { 0.0 } else { 1.0 };
            self.last_step = None;
            if stirred {
                gpu.clear(&target, self.backdrop);
                self.draws += 1;
            }
            return Frame { drew: stirred, wait: RESTING };
        }
        let night = halloween::night(self.settings.halloween && self.settings.modes == [Mode::Snow]);
        // The candle follows the finger even over snow that lies still.
        let lit = |at: V2| {
            let reach = LANTERN_REACH * 3.0;
            at.x > -reach && at.y > -reach && at.x < simulation.width() + reach && at.y < simulation.height() + reach
        };
        let lantern_moved = night > 0.0 && (pointer - self.lantern).length() > 0.5 && (lit(pointer) || lit(self.lantern));
        self.lantern = pointer;

        // A step toward shown, or gone under a full-screen app.
        let goal = if self.covered { 0.0 } else { 1.0 };
        let fading = self.opacity != goal;
        if fading {
            let step = dt / if self.covered { FADE_OUT } else { FADE_IN };
            self.opacity = if goal > self.opacity { (self.opacity + step).min(1.0) } else { (self.opacity - step).max(0.0) };
        }
        if self.covered && self.opacity <= 0.0 {
            gpu.clear(&target, self.backdrop);
            self.draws += 1;
            return Frame { drew: true, wait: HIDDEN };
        }

        let fastest = if self.budget == PowerBudget::Full { self.interactive_rate } else { self.interactive_rate.min(60) };
        // 24 only where it divides the display's rate evenly; elsewhere it would judder.
        let calm = if self.budget == PowerBudget::Saver && self.interactive_rate % 24 == 0 { 24 } else { 30 };
        let rate = match simulation.pace() {
            Pace::Calm => calm,
            Pace::Lively => fastest.min(60),
            Pace::Interactive => fastest,
        };
        let rate = if self.max_rate {
            self.interactive_rate
        } else if lantern_moved || fading {
            rate.max(fastest.min(60))
        } else {
            rate
        };
        let wait = 1_000_000_000 / rate.max(1) as i64;
        self.idle_frames = if changed || lantern_moved { 0 } else { self.idle_frames + 1 };
        // A still pile only needs a slow refresh for its glints, and the pumpkins for their candles' flicker; once
        // they are gone, until a frame without them is shown.
        let pumpkins = !simulation.pumpkins().is_empty();
        let slow_refresh = simulation.has_snow() || pumpkins || self.pumpkins_shown;
        if !(changed || lantern_moved || fading || stirred || (slow_refresh && self.idle_frames % 2 == 0)) {
            return Frame { drew: false, wait };
        }
        let ambient = Ambient { light: light::daylight(self.hour), shadow_tint: self.shadow_tint, night, lantern: pointer };
        let backdrop = match self.backdrop {
            Backdrop::Picture { .. } if !gpu.has_picture() => Backdrop::Night,
            backdrop => backdrop,
        };
        match gpu.draw(&target, simulation, ambient, self.panel, backdrop, time, self.opacity) {
            Ok(()) => {
                self.pumpkins_shown = pumpkins;
                self.draws += 1;
                Frame { drew: true, wait }
            }
            Err(_) => Frame { drew: false, wait },
        }
    }

    /// Hands the simulation the windows when they changed.
    fn follow(&mut self, now: i64) {
        if !self.windows_stale {
            return;
        }
        let (Some(simulation), Some(target)) = (&mut self.simulation, self.target) else { return };
        self.windows_stale = false;
        let placed = place(&self.reported, &target, simulation.width(), simulation.height(), self.settings.ledges);
        simulation.shelf = placed.shelf;
        if placed.panel != self.panel {
            self.panel = placed.panel;
            self.stirred = true;
        }
        if placed.surfaces == self.surfaces {
            return;
        }
        let since = (now - self.windows_changed_at).max(0) as f32 / 1e9;
        // A window that moves outside a drag, as the keyboard does when it comes up, jumps there in one go: its snow
        // is left behind instead of being carried along.
        let interval = if self.reported_moving || since < 0.1 { since.clamp(1.0 / 240.0, 0.25) } else { 1.0 / 60.0 };
        // Snow waits for a window out of sight that will be back.
        let elsewhere: HashSet<WindowId> = self
            .surfaces
            .iter()
            .map(|surface| surface.id)
            .filter(|id| !placed.surfaces.iter().any(|surface| surface.id == *id) && placed.away.contains(id))
            .collect();
        simulation.update_windows(&placed.surfaces, interval, &HashSet::new(), &elsewhere, false);
        self.surfaces = placed.surfaces;
        self.windows_changed_at = now;
        self.stirred = true;
    }

    /// What the scene is and how it is doing, for the log.
    pub fn describe(&self) -> String {
        let adapter = self.gpu.as_ref().map_or("no graphics", |gpu| gpu.adapter.as_str());
        let target = self
            .target
            .map_or("unplaced".to_string(), |t| format!("{}x{} px, snow {}x{} at {},{}, scale {}", t.width, t.height, t.w, t.h, t.x, t.y, t.scale));
        let snow = self.simulation.as_ref().map_or("no snow".to_string(), |simulation| {
            format!("{}x{} points, {} sprites, {:?}", simulation.width(), simulation.height(), simulation.sprite_count(), simulation.pace())
        });
        format!(
            "{adapter}; {target}; {snow}; {} windows; {:?}; {} Hz; {} frames, {} drawn",
            self.surfaces.len(),
            self.budget,
            self.interactive_rate,
            self.frames,
            self.draws
        )
    }
}

/// A point of the surface, in pixels from its top left, in the simulation's points from the bottom left of its part.
pub fn local(target: &Target, x: f32, y: f32) -> V2 {
    V2::new((x - target.x as f32) / target.scale, (target.height as f32 - y - target.y as f32) / target.scale)
}

fn new_simulation(width: f32, height: f32, settings: &Settings, budget: PowerBudget) -> SnowSimulation {
    let mut simulation = SnowSimulation::new(width, height, settings.intensity);
    simulation.thaws = settings.melt;
    simulation.accumulates = settings.ground || settings.ledges;
    simulation.ground_settles = settings.ground;
    simulation.falling_opacity = settings.opacity;
    simulation.set_modes(&settings.modes);
    simulation.set_density(density(budget));
    simulation.set_halloween(settings.halloween && settings.modes == [Mode::Snow]);
    // A phone has no display past its edges: snow slid off a window's end heaps up against them.
    simulation.walls = (true, true);
    simulation
}

/// Battery saver makes do with a little less snow.
fn density(budget: PowerBudget) -> f32 {
    if budget == PowerBudget::Saver { 0.75 } else { 1.0 }
}

#[cfg(test)]
mod tests {
    use super::*;

    const PHONE: Target = Target { width: 1080, height: 2340, x: 0, y: 0, w: 1080, h: 2340, scale: 3.0 };

    fn window(id: WindowId, left: f32, top: f32, right: f32, bottom: f32) -> Reported {
        Reported { id, left, top, right, bottom, radius: 0.0, settles: true, panel: false, away: false }
    }

    #[test]
    fn a_point_is_measured_up_from_the_snows_bottom_left() {
        let target = Target { y: 144, h: 2340 - 144, ..PHONE };
        assert_eq!(local(&target, 0.0, 2340.0 - 144.0), V2::new(0.0, 0.0));
        assert_eq!(local(&target, 300.0, 2340.0 - 144.0 - 600.0), V2::new(100.0, 200.0));
    }

    #[test]
    fn the_keyboard_holds_snow_and_hides_what_is_behind_it() {
        let keyboard = Reported { panel: true, radius: 0.0, ..window(7, 0.0, 1500.0, 1080.0, 2340.0) };
        let placed = place(&[keyboard], &PHONE, 360.0, 780.0, true);
        assert_eq!(placed.surfaces.len(), 1);
        assert_eq!(placed.surfaces[0].frame, Rect { x: 0.0, y: 0.0, width: 360.0, height: 280.0 });
        assert_eq!(placed.panel, Some([0.0, 0.0, 360.0, 280.0]));
        assert_eq!(placed.shelf, Some(7));
        // With no snow on windows it still stands in front of the snow.
        let placed = place(&[keyboard], &PHONE, 360.0, 780.0, false);
        assert!(placed.surfaces.is_empty());
        assert_eq!(placed.panel, Some([0.0, 0.0, 360.0, 280.0]));
        assert_eq!(placed.shelf, None);
    }

    #[test]
    fn a_window_filling_the_screen_or_touching_its_top_holds_none() {
        let full = window(1, 0.0, 0.0, 1080.0, 2340.0);
        let at_top = window(2, 90.0, 0.0, 990.0, 600.0);
        let small = window(3, 90.0, 900.0, 300.0, 1000.0);
        let placed = place(&[full, at_top, small], &PHONE, 360.0, 780.0, true);
        assert!(placed.surfaces.is_empty());
    }

    #[test]
    fn a_window_in_front_hides_the_snow_of_one_behind() {
        let front = window(1, 600.0, 600.0, 1050.0, 1200.0);
        let behind = window(2, 90.0, 900.0, 990.0, 1800.0);
        let placed = place(&[front, behind], &PHONE, 360.0, 780.0, true);
        assert_eq!(placed.surfaces.len(), 2);
        assert!(placed.surfaces[0].blockers.is_empty());
        assert_eq!(placed.surfaces[1].blockers.len(), 1);
        assert_eq!(placed.surfaces[1].cover.len(), 1);
    }

    #[test]
    fn a_card_scrolled_away_keeps_its_snow_waiting() {
        let away = Reported { away: true, ..window(4, 0.0, -900.0, 1080.0, -300.0) };
        let placed = place(&[away], &PHONE, 360.0, 780.0, true);
        assert!(placed.surfaces.is_empty());
        assert!(placed.away.contains(&4));
    }

    #[test]
    fn a_navigation_bar_coming_and_going_moves_the_ground() {
        let mut scene = Scene::new();
        scene.place(1080, 2340, 0, 0, 144, 3.0);
        let height = scene.simulation().unwrap().height();
        assert_eq!(height, (2340.0 - 144.0) / 3.0);
        scene.place(1080, 2340, 0, 0, 0, 3.0);
        assert_eq!(scene.simulation().unwrap().height(), 780.0);
        scene.place(1080, 2340, 0, 0, 144, 3.0);
        assert_eq!(scene.simulation().unwrap().height(), height);
        // Turned on its side, the bar stands along an edge and the snow is as wide as what is left.
        scene.place(2340, 1080, 144, 0, 0, 3.0);
        assert_eq!(scene.simulation().unwrap().width(), (2340.0 - 144.0) / 3.0);
        assert_eq!(scene.simulation().unwrap().height(), 360.0);
    }

    #[test]
    fn settings_made_before_the_surface_come_with_the_snow() {
        let mut scene = Scene::new();
        scene.set_intensity(0.8);
        scene.set_modes(&[Mode::Autumn], false, false);
        scene.set_surfaces(false, false);
        scene.place(1080, 2340, 0, 0, 0, 3.0);
        let simulation = scene.simulation().unwrap();
        assert_eq!(simulation.modes(), &[Mode::Autumn]);
        assert!(!simulation.ground_settles && !simulation.accumulates);
    }

    #[test]
    fn halloween_haunts_only_snow_falling_alone() {
        let mut scene = Scene::new();
        scene.set_halloween(true);
        assert!(scene.halloween());
        scene.set_modes(&[Mode::Snow, Mode::Rain], false, false);
        assert!(!scene.halloween());
        scene.set_modes(&[Mode::Snow], false, false);
        assert!(scene.halloween());
    }

    #[test]
    fn without_graphics_a_frame_only_waits() {
        let mut scene = Scene::new();
        scene.place(1080, 2340, 0, 0, 0, 3.0);
        assert_eq!(scene.frame(1_000_000), Frame { drew: false, wait: RESTING });
    }
}
