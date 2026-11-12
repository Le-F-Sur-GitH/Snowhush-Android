package app.snowhush.android;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * The snow's window over every app: as big as the screen, see-through, and out of the way of every touch, which goes
 * through to what is under the snow. Two things can put it up. With "Display over other apps", it sits above the apps
 * and under the keyboard and the bars, and Android lets touches through it only while it is no more than four fifths
 * opaque. As an accessibility service's, with Deep Interaction, it is above everything, at full strength.
 *
 * <p>It pauses while the screen sleeps or is locked, and, unless told to show over full screen, fades out of the way
 * of an app that has put the status bar away, as a video or a game does.
 */
final class OverlayWindow implements SurfaceHolder.Callback, SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "Snowhush";

    private final Context context;
    private final WindowManager windows;
    private final int type;
    private final boolean trusted;
    private final SharedPreferences prefs;
    private final SnowEngine engine;
    private final FrameLayout root;
    private final SurfaceView view;
    private boolean added;
    private boolean awake;
    private boolean unlocked;
    private boolean fullScreenByInsets;
    private boolean fullScreenByFlags;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                awake = false;
                // Whatever locks the screen from here says so again when it is unlocked.
                unlocked = !locked();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                awake = true;
                unlocked = !locked();
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                awake = true;
                unlocked = true;
            }
            update();
        }
    };

    /** {@code type} is the window's, the app overlay's or the accessibility overlay's; the second is {@code trusted}. */
    @SuppressWarnings("deprecation")
    OverlayWindow(Context context, int type, boolean trusted) {
        this.context = context;
        this.type = type;
        this.trusted = trusted;
        windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        prefs = Prefs.of(context);
        engine = new SnowEngine(context, SnowEngine.OVERLAY);
        root = new FrameLayout(context);
        view = new SurfaceView(context);
        view.setZOrderOnTop(true);
        view.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        view.getHolder().addCallback(this);
        root.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            read(insets);
            return insets;
        });
        // Before Android 11 a window only hears that the status bar went away this way.
        root.setOnSystemUiVisibilityChangeListener(visibility -> {
            fullScreenByFlags = (visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0;
            cover();
        });
    }

    SnowEngine engine() {
        return engine;
    }

    /** Puts the window up. Returns whether Android let it. */
    boolean show() {
        if (added) return true;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | (Build.VERSION.SDK_INT < 30 ? WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS : 0),
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.setTitle("Snowhush");
        if (Build.VERSION.SDK_INT >= 30) {
            // Over the bars and round the camera: the snow falls from the very top to the very bottom.
            params.setFitInsetsTypes(0);
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (!trusted && Build.VERSION.SDK_INT >= 31) {
            // The most opaque a window of another app's may be and still let touches through to what it covers.
            InputManager input = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
            params.alpha = input == null ? 0.8f : Math.min(1f, input.getMaximumObscuringOpacityForTouch());
        }
        try {
            windows.addView(root, params);
        } catch (RuntimeException refused) {
            Log.e(TAG, "the snow's window was refused", refused);
            return false;
        }
        added = true;
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        awake = power == null || power.isInteractive();
        unlocked = !locked();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        prefs.registerOnSharedPreferenceChangeListener(this);
        root.requestApplyInsets();
        update();
        return true;
    }

    /** Takes the window down for good. */
    void close() {
        if (added) {
            added = false;
            prefs.unregisterOnSharedPreferenceChangeListener(this);
            try {
                context.unregisterReceiver(receiver);
            } catch (IllegalArgumentException alreadyGone) {
                // Nothing to undo.
            }
            try {
                windows.removeViewImmediate(root);
            } catch (RuntimeException alreadyGone) {
                // The window went with its service.
            }
        }
        engine.release();
    }

    private boolean locked() {
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguard != null && keyguard.isKeyguardLocked();
    }

    /** In sight while the screen is on and unlocked; otherwise the surface is given back, and its memory with it. */
    private void update() {
        boolean shown = added && awake && unlocked;
        root.setVisibility(shown ? View.VISIBLE : View.GONE);
        engine.setShown(shown);
        if (shown) root.requestApplyInsets();
    }

    /** What the bars take of the screen, and whether the app in front has put the status bar away. */
    @SuppressWarnings("deprecation")
    private void read(WindowInsets insets) {
        int left;
        int right;
        int bottom;
        if (Build.VERSION.SDK_INT >= 30) {
            // A bar with buttons to tap; the thin handle of gesture navigation takes nothing, and the snow lies under it.
            Insets tappable = insets.getInsets(WindowInsets.Type.tappableElement());
            left = tappable.left;
            right = tappable.right;
            bottom = tappable.bottom;
            fullScreenByInsets = !insets.isVisible(WindowInsets.Type.statusBars());
        } else if (Build.VERSION.SDK_INT == 29) {
            Insets tappable = insets.getTappableElementInsets();
            left = tappable.left;
            right = tappable.right;
            bottom = tappable.bottom;
        } else {
            left = insets.getSystemWindowInsetLeft();
            right = insets.getSystemWindowInsetRight();
            bottom = insets.getSystemWindowInsetBottom();
        }
        if (Build.VERSION.SDK_INT < 30 && left == 0 && right == 0 && bottom == 0) bottom = legacyBar();
        engine.setInsets(left, right, bottom);
        cover();
    }

    /**
     * Before Android 11 a window laid out past the bars isn't told of them. The bar with buttons along the bottom is
     * as tall as Android's own resources say, while the phone is upright and navigated with buttons.
     */
    private int legacyBar() {
        Resources resources = context.getResources();
        if (resources.getDisplayMetrics().widthPixels > resources.getDisplayMetrics().heightPixels) return 0;
        int mode = resources.getIdentifier("config_navBarInteractionMode", "integer", "android");
        if (mode != 0 && resources.getInteger(mode) == 2) return 0;
        int shows = resources.getIdentifier("config_showNavigationBar", "bool", "android");
        if (shows != 0 && !resources.getBoolean(shows)) return 0;
        int height = resources.getIdentifier("navigation_bar_height", "dimen", "android");
        return height == 0 ? 0 : resources.getDimensionPixelSize(height);
    }

    private void cover() {
        boolean fullScreen = fullScreenByInsets || fullScreenByFlags;
        engine.setCovered(fullScreen && !prefs.getBoolean(Prefs.OVER_FULL_SCREEN, false));
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (Prefs.OVER_FULL_SCREEN.equals(key)) cover();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {}

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        engine.surfaceChanged(holder.getSurface(), width, height, context.getResources().getDisplayMetrics().density);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        engine.surfaceDestroyed();
    }
}
