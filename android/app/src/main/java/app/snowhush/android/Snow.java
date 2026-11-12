package app.snowhush.android;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;

/**
 * Where the snow over every app stands: whether it is wanted, what may put its window up, and who has. An
 * accessibility service, with Deep Interaction, comes before the plain overlay, which needs a foreground service to
 * stay up. Everything here is the main thread's.
 */
final class Snow {
    private static final String TAG = "Snowhush";
    private static OverlayWindow overlay;
    private static final ArrayList<Runnable> watchers = new ArrayList<>();

    private Snow() {}

    /** The snow over every app while its window is up, or null. */
    static SnowEngine overlay() {
        return overlay == null ? null : overlay.engine();
    }

    static void setOverlay(OverlayWindow window) {
        overlay = window;
        changed();
    }

    /** The window was taken down, unless another has taken its place since. */
    static void clearOverlay(OverlayWindow window) {
        if (overlay == window) {
            overlay = null;
            changed();
        }
    }

    /** Runs {@code watcher} whenever the snow over every app starts, stops or changes hands. */
    static void watch(Runnable watcher) {
        if (!watchers.contains(watcher)) watchers.add(watcher);
    }

    static void unwatch(Runnable watcher) {
        watchers.remove(watcher);
    }

    static void changed() {
        for (Runnable watcher : new ArrayList<>(watchers)) watcher.run();
    }

    /** Whether Deep Interaction is on: the accessibility service is running. */
    static boolean deep() {
        return DeepService.running() != null;
    }

    /** Whether anything may put the snow's window up. */
    static boolean allowed(Context context) {
        return deep() || Settings.canDrawOverlays(context);
    }

    /** Makes what is on the screen match what is wanted. */
    static void apply(Context context) {
        Context app = context.getApplicationContext();
        boolean wanted = Prefs.snowing(app) && Native.available();
        DeepService deep = DeepService.running();
        Intent service = new Intent(app, OverlayService.class);
        if (deep != null) {
            app.stopService(service);
            deep.setSnowing(wanted);
        } else if (wanted && Settings.canDrawOverlays(app)) {
            try {
                app.startForegroundService(service);
            } catch (RuntimeException refused) {
                // From the background Android may say no: the snow comes back when the app is next opened.
                Log.w(TAG, "the snow couldn't be started from here", refused);
            }
        } else {
            app.stopService(service);
        }
        changed();
    }
}
