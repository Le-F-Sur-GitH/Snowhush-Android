package app.snowhush.android;

import java.nio.ByteBuffer;

/**
 * Calls the engine exactly as the app does, through {@link Native}, from a desktop's Java: every native method is
 * looked up and run, so one that the Rust side names or shapes differently fails here instead of on a phone.
 */
public final class HostTest {
    private static final int WIDTH = 360;
    private static final int HEIGHT = 780;

    private static void check(boolean holds, String what) {
        if (!holds) throw new IllegalStateException(what);
    }

    private static int run(long scene, long[] now, int frames) {
        int drawn = 0;
        for (int i = 0; i < frames; i++) {
            long result = Native.frame(scene, now[0]);
            check(result >= 0, "the scene broke: " + Native.describe(scene));
            drawn += (int) (result & 1);
            now[0] += Math.min(Math.max(result >> 1, 1_000_000L), 40_000_000L);
        }
        return drawn;
    }

    public static void main(String[] args) {
        String folder = args.length > 0 ? args[0] : ".";
        check(Native.available(), "libsnowhush is not on java.library.path");
        String error = HostEgl.open(WIDTH, HEIGHT);
        check(error == null, "no context: " + error);
        check(!Native.version().isEmpty(), "no version");

        long scene = Native.create();
        check(scene != 0, "no scene");
        check(Native.frame(scene, 0) == 2_000_000_000L, "a scene with no surface should only wait");
        Native.set(scene, Native.INTENSITY, 1f, 0, 0, 0);
        Native.set(scene, Native.OPACITY, 1f, 0, 0, 0);
        Native.set(scene, Native.SURFACES, 1, 1, 0, 0);
        Native.set(scene, Native.MELT, 0, 0, 0, 0);
        Native.set(scene, Native.MODES, 1 << Prefs.SNOW, Prefs.SNOW, 0, 0);
        Native.set(scene, Native.POWER, 0, 0, 0, 0);
        Native.set(scene, Native.RATE, 120, 0, 0, 0);
        Native.set(scene, Native.HOUR, 21.5f, 0, 0, 0);
        Native.set(scene, Native.SHADOW, 0.2f, 0.3f, 0.6f, 1);
        // A phone with a bar of buttons 48 pixels tall, and the keyboard up over the bottom third.
        Native.place(scene, WIDTH, HEIGHT, 0, 0, 48, 1f);
        error = Native.glInit(scene);
        check(error == null, "no graphics: " + error);
        float[] windows = {
            7, 0, 520, WIDTH, HEIGHT, 0, Native.WINDOW_SETTLES | Native.WINDOW_PANEL, 0,
            8, 150, 200, 340, 310, 14, Native.WINDOW_SETTLES, 0,
            9, 0, -400, WIDTH, -100, 14, Native.WINDOW_AWAY, 0,
        };
        Native.windows(scene, windows, 3, false);
        long[] now = {1_000_000_000L};
        int drawn = run(scene, now, 400);
        check(drawn > 100, "falling snow should be drawn most frames, but only " + drawn + " were");
        String described = Native.describe(scene);
        check(described.contains("2 windows"), "the keyboard and the floating window should hold snow: " + described);
        check(!described.contains("failed"), described);
        error = HostEgl.save(scene, folder + "/overlay.png");
        check(error == null, "no picture: " + error);

        // The finger, a tilt, a shake, other weather, a full-screen app: nothing may break the scene.
        Native.set(scene, Native.POINTER, 180, 700, 1, 0);
        Native.set(scene, Native.WIND, 60, 0, 0, 0);
        run(scene, now, 30);
        Native.set(scene, Native.POINTER, 0, 0, 0, 0);
        Native.set(scene, Native.CLEAR, 0, 1, 0, 0);
        Native.set(scene, Native.MODES, (1 << Prefs.RAIN) | (1 << Prefs.AUTUMN), Prefs.RAIN, 1, 0);
        Native.set(scene, Native.HALLOWEEN, 1, 0, 0, 0);
        Native.windows(scene, new float[0], 0, true);
        run(scene, now, 120);
        Native.set(scene, Native.COVERED, 1, 0, 0, 0);
        run(scene, now, 40);
        Native.set(scene, Native.COVERED, 0, 0, 0, 0);
        Native.place(scene, HEIGHT, WIDTH, 48, 0, 0, 1f);
        Native.place(scene, WIDTH, HEIGHT, 0, 0, 0, 1f);
        run(scene, now, 40);

        // A wallpaper: its night, then a picture of its own, slid along by the home screen's pages.
        Native.set(scene, Native.MODES, 1 << Prefs.SNOW, Prefs.SNOW, 0, 0);
        Native.set(scene, Native.BACKDROP, 1, 0.5f, 1, 0);
        run(scene, now, 20);
        error = HostEgl.save(scene, folder + "/wallpaper.png");
        check(error == null, "no picture: " + error);
        ByteBuffer pixels = ByteBuffer.allocateDirect(8 * 4 * 4);
        for (int i = 0; i < 8 * 4; i++) pixels.put((byte) (i * 8)).put((byte) 80).put((byte) 120).put((byte) 255);
        pixels.rewind();
        Native.picture(scene, pixels, 8, 4);
        Native.set(scene, Native.BACKDROP, 2, 0.25f, 0.8f, 0);
        Native.set(scene, Native.SLIDE, -900, 0, 0, 0);
        run(scene, now, 20);
        Native.picture(scene, null, 0, 0);
        Native.set(scene, Native.RESUME, 0, 0, 0, 0);
        run(scene, now, 5);

        System.out.println(Native.describe(scene));
        Native.glDrop(scene);
        check(Native.frame(scene, now[0]) >= 0, "a scene without graphics should only wait");
        Native.destroy(scene);
        System.out.println("The engine answers every call the app makes.");
    }
}
