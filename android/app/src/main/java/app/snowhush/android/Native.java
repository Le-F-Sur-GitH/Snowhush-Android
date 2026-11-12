package app.snowhush.android;

import java.nio.ByteBuffer;

/**
 * The snow itself: the simulation the Windows and Linux apps share and the Linux app's shaders, built from
 * {@code android/native} into {@code libsnowhush.so}. A scene is the snow on one surface; every call for a scene is
 * made on that scene's own thread, the drawing ones with its graphics context current.
 */
final class Native {
    /** What {@link #set} sets, numbered as in {@code android/native/src/lib.rs}. */
    static final int INTENSITY = 0;
    static final int OPACITY = 1;
    /** Whether snow settles on the ground, and on the keyboard and windows. */
    static final int SURFACES = 2;
    static final int MELT = 3;
    /** The kinds falling as a mask, the one picked last, whether what lies is cleared away first, and whether calmly. */
    static final int MODES = 4;
    static final int HALLOWEEN = 5;
    /** 0 while charging, 1 on battery, 2 in battery saver. */
    static final int POWER = 6;
    /** The display's refresh rate, and whether every frame is drawn at it. */
    static final int RATE = 7;
    static final int HOUR = 8;
    /** The finger, in the surface's pixels from its top left, and whether it is down. */
    static final int POINTER = 9;
    /** Whether calmly, and which way the gust blows: right above zero, left below, the breeze's own way at zero. */
    static final int CLEAR = 10;
    /** The wind a tilted phone blows, in points a second. */
    static final int WIND = 11;
    /** How fast the home screen's pages slide past, in pixels a second. */
    static final int SLIDE = 12;
    static final int COVERED = 13;
    /** A colour of the wallpaper, red, green and blue from 0 to 1, and whether there is one. */
    static final int SHADOW = 14;
    /** 0 for a see-through surface, 1 for the night, 2 for the picture; how far along it the view is, and its light. */
    static final int BACKDROP = 15;
    static final int RESUME = 16;

    /** Numbers per window handed to {@link #windows}: id, left, top, right, bottom, radius, kind and one to spare. */
    static final int WINDOW_NUMBERS = 8;
    /** A window's kind: snow lies along its top edge. */
    static final int WINDOW_SETTLES = 1;
    /** It stands in front of the snow, as the keyboard does. */
    static final int WINDOW_PANEL = 2;
    /** It is out of sight for now, and its snow waits for it. */
    static final int WINDOW_AWAY = 4;

    private static final boolean LOADED = load();

    private Native() {}

    private static boolean load() {
        try {
            System.loadLibrary("snowhush");
            return true;
        } catch (UnsatisfiedLinkError error) {
            android.util.Log.e("Snowhush", "libsnowhush.so is missing or was built for another processor", error);
            return false;
        }
    }

    /** Whether the snow can fall at all. */
    static boolean available() {
        return LOADED;
    }

    static native long create();

    static native void destroy(long scene);

    /** Makes what the snow is drawn with, in the context current on this thread. Returns what went wrong, or null. */
    static native String glInit(long scene);

    /** The context is gone, or about to go. */
    static native void glDrop(long scene);

    /**
     * The surface's size in pixels, what a navigation bar with buttons takes of its left, right and bottom edges, and
     * how many pixels a point takes.
     */
    static native void place(long scene, int width, int height, int left, int right, int bottom, float scale);

    /**
     * Steps the snow to {@code now}, in nanoseconds, and draws it if it changed. Returns twice the nanoseconds until
     * the next frame is due, plus one when something was drawn and the surface wants showing; below zero when the
     * scene is broken for good.
     */
    static native long frame(long scene, long now);

    static native void set(long scene, int what, float a, float b, float c, float d);

    /** The windows on the screen, front to back; {@code moving} while one is dragged or scrolled. */
    static native void windows(long scene, float[] windows, int count, boolean moving);

    /** A wallpaper's picture as red, green, blue and alpha bytes in a direct buffer, rows from the top, or null. */
    static native void picture(long scene, ByteBuffer pixels, int width, int height);

    /** What the scene is and how it is doing, for the log. */
    static native String describe(long scene);

    static native String version();
}
