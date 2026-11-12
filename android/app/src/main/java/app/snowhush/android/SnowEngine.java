package app.snowhush.android;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.Display;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Calendar;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The snow on one surface: a thread that owns the surface's graphics context and the scene drawn in it, steps the
 * scene at the pace it asks for, and tells it what Android knows, as {@code linux/src/snow.rs} does for a display:
 * the settings, the power, the hour, the wallpaper's colour, the windows and the finger. The overlay over every app,
 * a live wallpaper and the snow over the app's own settings each have one.
 *
 * <p>Everything public is called on the main thread; the scene is only ever touched on the snow's.
 */
final class SnowEngine implements SharedPreferences.OnSharedPreferenceChangeListener, Motion.Listener {
    /** Over every app: see-through, lying on top of a navigation bar with buttons, out of a full-screen app's way. */
    static final int OVERLAY = 0;
    /** A live wallpaper: nothing is behind the snow but its own backdrop. */
    static final int WALLPAPER = 1;
    /** Over the app's own settings, whose cards are its windows. */
    static final int PREVIEW = 2;

    private static final String TAG = "Snowhush";
    private static final int EGL_CONTEXT_LOST = 0x300E;
    /** How much of a wallpaper picture's light is kept, so the snow stands out against it. */
    private static final float PICTURE_DIM = 0.82f;

    private final Context context;
    private final int kind;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread;
    private final Handler snow;
    private final Motion motion;
    private final DisplayManager displays;
    private boolean listening;
    private boolean released;
    /** Whether the snow may be drawn: its surface is in sight and the screen is on and unlocked. Main thread. */
    private boolean shown;

    // Everything below belongs to the snow's thread.
    private long scene;
    private Choreographer choreographer;
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLConfig eglConfig;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private Surface surface;
    private int width;
    private int height;
    private int insetLeft;
    private int insetRight;
    private int insetBottom;
    private float scale = 1f;
    private boolean active;
    private boolean broken;
    private boolean scheduled;
    private long dueAt;
    private float refresh = 60f;
    private int voted;
    private int[] modes;
    private float[] systemWindows = new float[0];
    private int systemCount;
    private float[] appWindows = new float[0];
    private int appCount;
    private boolean appMoving;
    private int pictureShown = -1;
    private boolean pictured;
    private float slide = 0.5f;

    private final Choreographer.FrameCallback tick = this::frame;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_TIME_TICK.equals(action) || Intent.ACTION_TIME_CHANGED.equals(action) || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
                pushClock();
            } else {
                pushPower();
            }
        }
    };

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int id) {}

        @Override
        public void onDisplayRemoved(int id) {}

        @Override
        public void onDisplayChanged(int id) {
            if (id == Display.DEFAULT_DISPLAY) pushRate();
        }
    };

    /** Kept for as long as the engine listens: the wallpaper manager holds on to it. */
    private Object colorsListener;

    SnowEngine(Context context, int kind) {
        this.context = context.getApplicationContext();
        this.kind = kind;
        prefs = Prefs.of(context);
        displays = (DisplayManager) this.context.getSystemService(Context.DISPLAY_SERVICE);
        thread = new HandlerThread("snow", Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        snow = new Handler(thread.getLooper());
        motion = new Motion(this.context, main, this);
        snow.post(() -> {
            choreographer = Choreographer.getInstance();
            if (Native.available()) scene = Native.create();
            if (scene == 0) broken = true;
        });
        pushSettings();
        pushRate();
        pushPower();
        pushClock();
        pushShadow();
    }

    // The surface

    /** The surface is there, or changed its size: {@code width} by {@code height} pixels at {@code scale} to a point. */
    void surfaceChanged(Surface surface, int width, int height, float scale) {
        snow.post(() -> attach(surface, width, height, scale));
    }

    /** The surface is going: returns once the snow has let go of it. */
    void surfaceDestroyed() {
        await(this::detach);
    }

    /** What a navigation bar with buttons takes of the surface's left, right and bottom edges, in pixels. */
    void setInsets(int left, int right, int bottom) {
        snow.post(() -> {
            if (left == insetLeft && right == insetRight && bottom == insetBottom) return;
            insetLeft = left;
            insetRight = right;
            insetBottom = bottom;
            place();
        });
    }

    /**
     * Whether the snow is in sight, with the screen on and unlocked. Out of sight it stops, and the phone's tilt goes
     * unread; back in sight it goes on from where it stopped.
     */
    void setShown(boolean shown) {
        if (released || shown == this.shown) return;
        this.shown = shown;
        listen(shown);
        snow.post(() -> {
            active = shown;
            if (scene == 0) return;
            if (shown) {
                Native.set(scene, Native.RESUME, 0, 0, 0, 0);
                wake();
            } else if (scheduled) {
                scheduled = false;
                choreographer.removeFrameCallback(tick);
            }
        });
    }

    /** Stops the snow and gives everything back. The engine is of no use after. */
    void release() {
        if (released) return;
        released = true;
        shown = false;
        listen(false);
        await(() -> {
            detach();
            if (scene != 0) Native.destroy(scene);
            scene = 0;
        });
        thread.quitSafely();
    }

    // What the app tells

    /** The finger, in the surface's pixels from its top left, or lifted. */
    void pointer(float x, float y, boolean down) {
        set(Native.POINTER, x, y, down ? 1 : 0, 0);
    }

    /** Clears what lies: a gust blows it away, or, for less motion, it melts where it is. */
    void clear() {
        set(Native.CLEAR, Prefs.calm(context) ? 1 : 0, 0, 0, 0);
    }

    /** Fades the snow out of a full-screen app's way, or back in. */
    void setCovered(boolean covered) {
        set(Native.COVERED, covered ? 1 : 0, 0, 0, 0);
    }

    /** How far along the home screen's pages the view is, from 0 to 1, and how fast they slide past, in pixels a second. */
    void pages(float along, float velocity) {
        snow.post(() -> {
            if (scene == 0) return;
            slide = along;
            if (pictured) Native.set(scene, Native.BACKDROP, 2, slide, PICTURE_DIM, 0);
            if (velocity != 0f) Native.set(scene, Native.SLIDE, velocity, 0, 0, 0);
            wake();
        });
    }

    /**
     * The windows Android tells of, front to back, {@link Native#WINDOW_NUMBERS} numbers each: the keyboard,
     * picture-in-picture, a split screen's lower half.
     */
    void setSystemWindows(float[] windows, int count) {
        snow.post(() -> {
            systemWindows = windows;
            systemCount = count;
            pushWindows();
        });
    }

    /** The app's own cards, behind any of Android's windows; {@code moving} while they scroll. */
    void setAppWindows(float[] windows, int count, boolean moving) {
        snow.post(() -> {
            appWindows = windows;
            appCount = count;
            appMoving = moving;
            pushWindows();
        });
    }

    private void pushWindows() {
        if (scene == 0) return;
        float[] all = new float[(systemCount + appCount) * Native.WINDOW_NUMBERS];
        System.arraycopy(systemWindows, 0, all, 0, systemCount * Native.WINDOW_NUMBERS);
        System.arraycopy(appWindows, 0, all, systemCount * Native.WINDOW_NUMBERS, appCount * Native.WINDOW_NUMBERS);
        Native.windows(scene, all, systemCount + appCount, appMoving);
        wake();
    }

    private void set(int what, float a, float b, float c, float d) {
        snow.post(() -> {
            if (scene == 0) return;
            Native.set(scene, what, a, b, c, d);
            wake();
        });
    }

    // What Android tells

    private void listen(boolean on) {
        if (on == listening) return;
        listening = on;
        if (on) {
            prefs.registerOnSharedPreferenceChangeListener(this);
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_TIME_TICK);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_POWER_CONNECTED);
            filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
            filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            if (displays != null) displays.registerDisplayListener(displayListener, main);
            watchWallpaper(true);
            // Whatever changed while the snow was out of sight.
            pushSettings();
            pushRate();
            pushPower();
            pushClock();
            pushShadow();
        } else {
            prefs.unregisterOnSharedPreferenceChangeListener(this);
            try {
                context.unregisterReceiver(receiver);
            } catch (IllegalArgumentException alreadyGone) {
                // Nothing to undo.
            }
            if (displays != null) displays.unregisterDisplayListener(displayListener);
            watchWallpaper(false);
        }
        updateMotion();
    }

    private void updateMotion() {
        if (listening && prefs.getBoolean(Prefs.MOTION, true)) {
            motion.start();
        } else {
            motion.stop();
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (Prefs.MOTION.equals(key)) updateMotion();
        pushSettings();
    }

    @Override
    public void onWind(float wind) {
        set(Native.WIND, wind, 0, 0, 0);
    }

    @Override
    public void onShake(float direction) {
        set(Native.CLEAR, 0, direction, 0, 0);
    }

    private void pushSettings() {
        final boolean calm = Prefs.calm(context);
        final float intensity = Prefs.intensity(prefs, calm) / 100f;
        final float opacity = Prefs.opacity(prefs) / 100f;
        final boolean ground = prefs.getBoolean(Prefs.GROUND, true);
        // A wallpaper is behind every window: there is none for its snow to lie on.
        final boolean windows = kind != WALLPAPER && prefs.getBoolean(Prefs.WINDOWS, true);
        final boolean melt = prefs.getBoolean(Prefs.MELT, true);
        final boolean performance = prefs.getBoolean(Prefs.PERFORMANCE, false);
        final int[] picked = Prefs.modes(prefs);
        final boolean halloween = Prefs.halloweenWanted(prefs, Calendar.getInstance());
        final int picture = prefs.getInt(Prefs.PICTURE, 0);
        snow.post(() -> {
            if (scene == 0) return;
            Native.set(scene, Native.INTENSITY, intensity, 0, 0, 0);
            Native.set(scene, Native.OPACITY, opacity, 0, 0, 0);
            Native.set(scene, Native.SURFACES, ground ? 1 : 0, windows ? 1 : 0, 0, 0);
            Native.set(scene, Native.MELT, melt ? 1 : 0, 0, 0, 0);
            if (!Arrays.equals(picked, modes)) {
                // Picked while the snow falls, the new kinds come once what lies is cleared away; at the start, at once.
                Native.set(scene, Native.MODES, Prefs.mask(picked), picked[picked.length - 1], modes == null ? 0 : 1, calm ? 1 : 0);
                modes = picked;
            }
            Native.set(scene, Native.HALLOWEEN, halloween ? 1 : 0, 0, 0, 0);
            Native.set(scene, Native.RATE, refresh, performance ? 1 : 0, 0, 0);
            if (kind == WALLPAPER && picture != pictureShown && eglSurface != EGL14.EGL_NO_SURFACE) loadPicture();
            wake();
        });
    }

    private void pushRate() {
        Display display = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
        final float rate = display == null ? 60f : display.getRefreshRate();
        final boolean performance = prefs.getBoolean(Prefs.PERFORMANCE, false);
        snow.post(() -> {
            refresh = rate >= 30f && rate <= 480f ? rate : 60f;
            if (scene != 0) Native.set(scene, Native.RATE, refresh, performance ? 1 : 0, 0, 0);
        });
    }

    private void pushPower() {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        BatteryManager battery = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        int budget = power != null && power.isPowerSaveMode() ? 2 : battery != null && battery.isCharging() ? 0 : 1;
        set(Native.POWER, budget, 0, 0, 0);
    }

    /** The hour the light follows, and Halloween with it, which comes and goes by the clock. */
    private void pushClock() {
        Calendar now = Calendar.getInstance();
        final float hour = now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60f;
        final boolean halloween = Prefs.halloweenWanted(prefs, now);
        snow.post(() -> {
            if (scene == 0) return;
            Native.set(scene, Native.HOUR, hour, 0, 0, 0);
            Native.set(scene, Native.HALLOWEEN, halloween ? 1 : 0, 0, 0, 0);
        });
    }

    /** The shadows take on the wallpaper's colour, as on the desktops, where there is a wallpaper behind the snow. */
    private void pushShadow() {
        if (kind != OVERLAY || Build.VERSION.SDK_INT < 27) return;
        try {
            WallpaperManager wallpaper = WallpaperManager.getInstance(context);
            WallpaperColors colors = wallpaper == null ? null : wallpaper.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
            if (colors == null) {
                set(Native.SHADOW, 0, 0, 0, 0);
            } else {
                Color color = colors.getPrimaryColor();
                set(Native.SHADOW, color.red(), color.green(), color.blue(), 1);
            }
        } catch (RuntimeException unknown) {
            set(Native.SHADOW, 0, 0, 0, 0);
        }
    }

    private void watchWallpaper(boolean on) {
        if (kind != OVERLAY || Build.VERSION.SDK_INT < 27) return;
        try {
            WallpaperManager wallpaper = WallpaperManager.getInstance(context);
            if (wallpaper == null) return;
            if (on) {
                WallpaperManager.OnColorsChangedListener listener = (colors, which) -> {
                    if ((which & WallpaperManager.FLAG_SYSTEM) != 0) pushShadow();
                };
                colorsListener = listener;
                wallpaper.addOnColorsChangedListener(listener, main);
            } else if (colorsListener != null) {
                wallpaper.removeOnColorsChangedListener((WallpaperManager.OnColorsChangedListener) colorsListener);
                colorsListener = null;
            }
        } catch (RuntimeException unsupported) {
            // The shadows stay neutral.
        }
    }

    // The snow's thread

    private void attach(Surface next, int nextWidth, int nextHeight, float nextScale) {
        if (broken || scene == 0 || next == null || !next.isValid()) return;
        if (next != surface || eglSurface == EGL14.EGL_NO_SURFACE) {
            detach();
            if (!open(next)) {
                detach();
                return;
            }
            String error = Native.glInit(scene);
            if (error != null) {
                Log.e(TAG, "the snow can't be drawn: " + error);
                broken = true;
                detach();
                return;
            }
            surface = next;
            voted = 0;
            pictureShown = -1;
            if (kind == WALLPAPER) loadPicture();
        }
        width = nextWidth;
        height = nextHeight;
        scale = nextScale > 0f ? nextScale : 1f;
        place();
        Log.i(TAG, Native.describe(scene));
    }

    /** A context for OpenGL ES 3 with a see-through surface of eight bits a channel, current on this thread. */
    private boolean open(Surface target) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            Log.e(TAG, "no EGL display");
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            return false;
        }
        int[] wanted = {
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        };
        EGLConfig[] configs = new EGLConfig[64];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, wanted, 0, configs, 0, configs.length, count, 0) || count[0] == 0) {
            Log.e(TAG, "no EGL config with alpha");
            return false;
        }
        // Deeper colours come first in the list where a phone has them; the surface is eight bits a channel.
        eglConfig = configs[0];
        int[] bits = new int[1];
        for (int i = 0; i < count[0]; i++) {
            boolean exact = true;
            for (int channel : new int[] {EGL14.EGL_RED_SIZE, EGL14.EGL_GREEN_SIZE, EGL14.EGL_BLUE_SIZE, EGL14.EGL_ALPHA_SIZE}) {
                exact &= EGL14.eglGetConfigAttrib(eglDisplay, configs[i], channel, bits, 0) && bits[0] == 8;
            }
            if (exact) {
                eglConfig = configs[i];
                break;
            }
        }
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
        if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "no OpenGL ES 3 context: " + Integer.toHexString(EGL14.eglGetError()));
            eglContext = EGL14.EGL_NO_CONTEXT;
            return false;
        }
        try {
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, target, new int[] {EGL14.EGL_NONE}, 0);
        } catch (RuntimeException gone) {
            eglSurface = EGL14.EGL_NO_SURFACE;
        }
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "no EGL surface: " + Integer.toHexString(EGL14.eglGetError()));
            eglSurface = EGL14.EGL_NO_SURFACE;
            return false;
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "EGL make current: " + Integer.toHexString(EGL14.eglGetError()));
            return false;
        }
        return true;
    }

    /** Lets go of the surface and of everything made to draw on it; the snow itself stays as it is. */
    private void detach() {
        if (scheduled) {
            scheduled = false;
            choreographer.removeFrameCallback(tick);
        }
        if (scene != 0) Native.glDrop(scene);
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
        }
        eglSurface = EGL14.EGL_NO_SURFACE;
        eglContext = EGL14.EGL_NO_CONTEXT;
        surface = null;
        pictured = false;
    }

    private void place() {
        if (scene == 0 || eglSurface == EGL14.EGL_NO_SURFACE || width <= 0 || height <= 0) return;
        // A wallpaper reaches behind the navigation bar, which the launcher draws over it.
        boolean bars = kind != WALLPAPER;
        Native.place(scene, width, height, bars ? insetLeft : 0, bars ? insetRight : 0, bars ? insetBottom : 0, scale);
        Native.set(scene, Native.BACKDROP, kind != WALLPAPER ? 0 : pictured ? 2 : 1, slide, PICTURE_DIM, 0);
        wake();
    }

    /** Reads the wallpaper's picture, no bigger than the surface needs, and hands it to the scene. */
    private void loadPicture() {
        int stamp = prefs.getInt(Prefs.PICTURE, 0);
        pictureShown = stamp;
        pictured = false;
        File file = new File(context.getFilesDir(), Prefs.PICTURE_FILE);
        if (stamp != 0 && file.isFile() && scene != 0) {
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getPath(), options);
                int longest = Math.max(Math.max(width, height), 1280);
                options.inSampleSize = 1;
                while (Math.max(options.outWidth, options.outHeight) / (options.inSampleSize * 2) >= longest) options.inSampleSize *= 2;
                options.inJustDecodeBounds = false;
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
                if (bitmap != null && bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
                    Bitmap converted = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                    bitmap.recycle();
                    bitmap = converted;
                }
                if (bitmap != null) {
                    ByteBuffer pixels = ByteBuffer.allocateDirect(bitmap.getWidth() * bitmap.getHeight() * 4).order(ByteOrder.nativeOrder());
                    bitmap.copyPixelsToBuffer(pixels);
                    pixels.rewind();
                    Native.picture(scene, pixels, bitmap.getWidth(), bitmap.getHeight());
                    pictured = true;
                    bitmap.recycle();
                }
            } catch (RuntimeException | OutOfMemoryError unreadable) {
                Log.w(TAG, "the wallpaper's picture can't be read", unreadable);
            }
        }
        if (!pictured && scene != 0) Native.picture(scene, null, 0, 0);
        if (scene != 0) Native.set(scene, Native.BACKDROP, pictured ? 2 : 1, slide, PICTURE_DIM, 0);
    }

    private boolean ready() {
        return scene != 0 && !broken && active && eglSurface != EGL14.EGL_NO_SURFACE;
    }

    /** Draws the next frame now instead of when it was due, unless that is soon anyway. */
    private void wake() {
        if (!ready()) return;
        if (scheduled && dueAt - SystemClock.uptimeMillis() <= 34) return;
        schedule(0);
    }

    private void schedule(long delay) {
        if (scheduled) choreographer.removeFrameCallback(tick);
        scheduled = true;
        dueAt = SystemClock.uptimeMillis() + delay;
        if (delay <= 0) {
            choreographer.postFrameCallback(tick);
        } else {
            choreographer.postFrameCallbackDelayed(tick, delay);
        }
    }

    private void frame(long frameTime) {
        scheduled = false;
        if (!ready()) return;
        long result = Native.frame(scene, frameTime);
        if (result < 0) {
            broken = true;
            Log.e(TAG, "the snow stopped: " + Native.describe(scene));
            detach();
            return;
        }
        if ((result & 1) != 0 && !EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            int error = EGL14.eglGetError();
            Log.w(TAG, "the frame couldn't be shown: " + Integer.toHexString(error) + (error == EGL_CONTEXT_LOST ? " (context lost)" : ""));
            // The surface or the context is gone: a new surface brings new ones.
            detach();
            return;
        }
        long wait = result >> 1;
        vote(wait);
        // Frames are shown at the display's refreshes: the next is the first one no more than half a refresh early.
        long period = (long) (1e9f / refresh);
        long delay = (wait - (System.nanoTime() - frameTime) - period / 2) / 1_000_000;
        schedule(Math.max(0, delay));
    }

    /** Tells the display how often the snow is drawn, so it needn't refresh any faster on the snow's account. */
    private void vote(long wait) {
        if (Build.VERSION.SDK_INT < 30 || surface == null) return;
        int rate = wait > 0 && wait <= 50_000_000 ? Math.round(1e9f / wait) : 0;
        if (rate == voted) return;
        voted = rate;
        try {
            surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
        } catch (RuntimeException gone) {
            // The surface is on its way out.
        }
    }

    /** Runs on the snow's thread and waits for it, as when a surface must be let go of before it is destroyed. */
    private void await(Runnable work) {
        if (Looper.myLooper() == snow.getLooper()) {
            work.run();
            return;
        }
        final CountDownLatch done = new CountDownLatch(1);
        boolean posted = snow.post(() -> {
            try {
                work.run();
            } finally {
                done.countDown();
            }
        });
        if (!posted) return;
        try {
            if (!done.await(3, TimeUnit.SECONDS)) Log.w(TAG, "the snow's thread is busy");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
