package app.snowhush.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.util.Calendar;

/**
 * The settings, under the names the Linux app's settings file gives the ones it has too, and what follows from them:
 * which kinds fall, and whether it is Halloween. The same rules as {@code linux/src/prefs.rs} and the core's
 * {@code halloween.rs}.
 */
final class Prefs {
    static final String FILE = "settings";

    /** How much falls, in percent. */
    static final String INTENSITY = "intensity";
    /** How opaque what falls is drawn, in percent. */
    static final String OPACITY = "fallingOpacity";
    static final String GROUND = "settleGround";
    /** Snow settles on the home screen's widgets, and on its apps' icons: both need Deep Interaction. */
    static final String WIDGETS = "snowOnWidgets";
    static final String ICONS = "snowOnIcons";
    /** How much higher than worked out the snow lies on the icons, in density-independent pixels, to fit a launcher. */
    static final String ICON_LIFT = "iconLift";
    static final int ICON_LIFT_REACH = 24;
    static final String ICON_WIDTH = "iconWidth";
    static final int ICON_WIDTH_REACH = 20;
    static final String MELT = "meltSnow";
    /** What falls: the kind picked last. */
    static final String MODE = "mode";
    /** Two kinds fall together: mix0, the one picked before, and mix1, the one picked last. */
    static final String MIXED = "mixed";
    static final String MIX0 = "mix0";
    static final String MIX1 = "mix1";
    static final String HALLOWEEN_ON = "halloweenOn";
    /** The year whose Halloween was turned off, or zero. */
    static final String HALLOWEEN_SKIPPED = "halloweenSkipped";
    static final String PERFORMANCE = "performance";
    /** Tilting the phone blows the snowfall, shaking it blows the drift away. */
    static final String MOTION = "motion";
    /** Goes up each time the live wallpaper's picture is chosen or removed; zero while there is none. */
    static final String PICTURE = "wallpaperPicture";
    /** The live wallpaper's picture, in the app's files. */
    static final String PICTURE_FILE = "wallpaper.jpg";
    /** The picture as it was chosen, whole, which the one shown is framed out of. */
    static final String PICTURE_SOURCE = "wallpaper-source.jpg";
    /** How the picture was framed: how many times bigger than filling the screen, and the point of it at the middle. */
    static final String PICTURE_ZOOM = "pictureZoom";
    static final String PICTURE_X = "pictureX";
    static final String PICTURE_Y = "pictureY";

    static final int SNOW = 0;
    static final int AUTUMN = 1;
    static final int RAIN = 2;
    static final int BLOSSOM = 3;

    private Prefs() {}

    static SharedPreferences of(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Whether the person asked Android for less motion, by turning animations off. */
    static boolean calm(Context context) {
        return Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
    }

    static int intensity(SharedPreferences prefs, boolean calm) {
        return clamp(prefs.getInt(INTENSITY, calm ? 25 : 50), 0, 100);
    }

    static int iconLift(SharedPreferences prefs) {
        return clamp(prefs.getInt(ICON_LIFT, 0), -ICON_LIFT_REACH, ICON_LIFT_REACH);
    }

    static int iconWidth(SharedPreferences prefs) {
        return clamp(prefs.getInt(ICON_WIDTH, 0), -ICON_WIDTH_REACH, ICON_WIDTH_REACH);
    }

    static int opacity(SharedPreferences prefs) {
        return clamp(prefs.getInt(OPACITY, 100), 10, 100);
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private static int kind(int value) {
        return value >= SNOW && value <= BLOSSOM ? value : SNOW;
    }

    static int mode(SharedPreferences prefs) {
        return kind(prefs.getInt(MODE, SNOW));
    }

    /** The kind to mix with {@code mode} until another is picked. */
    private static int otherThan(int mode) {
        return mode == SNOW ? AUTUMN : SNOW;
    }

    /** The two kinds mixed, the one picked last second: read from the settings, or the mode and another. */
    static int[] mix(SharedPreferences prefs) {
        int mode = mode(prefs);
        int first = prefs.getInt(MIX0, -1);
        int second = prefs.getInt(MIX1, -1);
        if (first >= SNOW && first <= BLOSSOM && second >= SNOW && second <= BLOSSOM && first != second) {
            return new int[] {first, second};
        }
        return new int[] {otherThan(mode), mode};
    }

    /** The kinds falling, in the order they were picked: the two mixed, or the one picked. */
    static int[] modes(SharedPreferences prefs) {
        return prefs.getBoolean(MIXED, false) ? mix(prefs) : new int[] {mode(prefs)};
    }

    static boolean falls(int[] modes, int mode) {
        for (int falling : modes) {
            if (falling == mode) return true;
        }
        return false;
    }

    /** The kinds as the engine takes them: a bit for each. */
    static int mask(int[] modes) {
        int mask = 0;
        for (int mode : modes) mask |= 1 << mode;
        return mask;
    }

    /**
     * Picks a kind; mixed, a third kind picked takes the place of the one picked before the other. Returns whether
     * anything changed.
     */
    static boolean pick(SharedPreferences prefs, int mode) {
        SharedPreferences.Editor editor = prefs.edit();
        if (prefs.getBoolean(MIXED, false)) {
            int[] mix = mix(prefs);
            if (mix[0] == mode || mix[1] == mode) return false;
            editor.putInt(MIX0, mix[1]).putInt(MIX1, mode);
        } else if (mode == mode(prefs)) {
            return false;
        }
        editor.putInt(MODE, mode).apply();
        return true;
    }

    /** Mixed, the kind picked last falls along with the one picked before it; unmixed, it falls alone. */
    static void toggleMixed(SharedPreferences prefs) {
        boolean mixed = !prefs.getBoolean(MIXED, false);
        SharedPreferences.Editor editor = prefs.edit().putBoolean(MIXED, mixed);
        if (mixed) {
            int mode = mode(prefs);
            int[] mix = mix(prefs);
            int other = mix[0] == mode ? mix[1] : mix[1] == mode ? mix[0] : mix[1];
            if (other == mode) other = otherThan(mode);
            editor.putInt(MIX0, other).putInt(MIX1, mode);
        }
        editor.apply();
    }

    /** Whether it is Halloween at this local date and hour: from October 30 until dawn on November 1. */
    static boolean season(Calendar now) {
        int month = now.get(Calendar.MONTH) + 1;
        int day = now.get(Calendar.DAY_OF_MONTH);
        int hour = now.get(Calendar.HOUR_OF_DAY);
        return month == 10 && day >= 30 || month == 11 && day == 1 && hour < 6;
    }

    /** Whether Halloween is wanted now: turned on, or come with its season and not turned off for it. */
    static boolean halloweenWanted(SharedPreferences prefs, Calendar now) {
        return prefs.getBoolean(HALLOWEEN_ON, false) || season(now) && prefs.getInt(HALLOWEEN_SKIPPED, 0) != now.get(Calendar.YEAR);
    }

    /** Turned on or off by hand. Turned on in season, it ends with the season; any other day, it stays on. */
    static void toggleHalloween(SharedPreferences prefs, Calendar now) {
        boolean wanted = halloweenWanted(prefs, now);
        boolean season = season(now);
        SharedPreferences.Editor editor = prefs.edit();
        if (wanted && season) {
            editor.putBoolean(HALLOWEEN_ON, false).putInt(HALLOWEEN_SKIPPED, now.get(Calendar.YEAR));
        } else if (wanted) {
            editor.putBoolean(HALLOWEEN_ON, false);
        } else if (season) {
            editor.putInt(HALLOWEEN_SKIPPED, 0);
        } else {
            editor.putBoolean(HALLOWEEN_ON, true);
        }
        editor.apply();
    }

    /** Halloween only shows over snow falling alone. */
    static boolean snowAlone(int[] modes) {
        return modes.length == 1 && modes[0] == SNOW;
    }
}
