//! The light the snow is drawn in: warmer at dawn and dusk, cooler at night, with shadows taking on the colour of the
//! wallpaper, as on the desktops. The clock and the wallpaper's colour are Android's to tell, so Java hands them in.

use snowhush_core::geometry::smoothstep;

/// Clock-based rather than solar: close enough for mood, and needs no location.
pub fn daylight(hour: f32) -> [f32; 3] {
    let day = smoothstep(6.0, 8.5, hour) * (1.0 - smoothstep(17.5, 20.5, hour));
    let golden = ((-((hour - 7.6) / 1.1).powi(2)).exp() + (-((hour - 18.6) / 1.2).powi(2)).exp()).min(1.0);
    let night = [0.86, 0.89, 0.97];
    let warm = [1.0, 0.965, 0.915];
    std::array::from_fn(|i| {
        let base = night[i] + (1.0 - night[i]) * day;
        base + (warm[i] - base) * golden * 0.55
    })
}

/// A colour of the wallpaper, brought to the brightness of snow in shade.
pub fn tint(color: [f32; 3]) -> [f32; 4] {
    let luminance = color[0] * 0.2126 + color[1] * 0.7152 + color[2] * 0.0722;
    let shade = 0.775 / luminance.max(0.04);
    [(color[0] * shade).min(1.0), (color[1] * shade).min(1.0), (color[2] * shade).min(1.0), 0.35]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn noon_is_white_and_night_is_blue() {
        let noon = daylight(12.0);
        assert!(noon.iter().all(|channel| (channel - 1.0).abs() < 0.01));
        let night = daylight(1.0);
        assert!(night[2] > night[0]);
    }

    #[test]
    fn a_dark_wallpaper_still_shades_the_snow_lightly() {
        let shade = tint([0.02, 0.02, 0.05]);
        assert!(shade.iter().take(3).all(|channel| *channel > 0.3 && *channel <= 1.0));
        assert_eq!(shade[3], 0.35);
    }
}
