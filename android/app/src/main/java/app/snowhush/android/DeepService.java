package app.snowhush.android;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deep Interaction. An accessibility service is the one thing Android tells where the windows are, which is what the
 * window trackers of the desktop apps ask their systems: with it the snow settles on the keyboard, on a
 * picture-in-picture window and on the lower half of a split screen, and drops when they go. Its window is also the
 * one kind Android trusts to let touches through at full strength, above everything.
 *
 * <p>Only the windows' places and sizes are read. What they show never is: nothing here asks for a window's contents,
 * and the app has no way to send anything anywhere.
 */
public final class DeepService extends AccessibilityService {
    private static DeepService running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable report = this::report;
    private OverlayWindow window;
    private boolean pending;

    /** The service while Android has it running, or null. */
    static DeepService running() {
        return running;
    }

    @Override
    protected void onServiceConnected() {
        running = this;
        // Nothing is heard of the windows until the snow is up to lie on them.
        listen(false);
        Snow.apply(this);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stop();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        stop();
        super.onDestroy();
    }

    /** Turned off in Android's settings: the plain overlay takes over if it may. */
    private void stop() {
        if (running != this) return;
        running = null;
        setSnowing(false);
        Snow.apply(getApplicationContext());
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (window == null || pending) return;
        // Windows come and go in bursts; one look once they settle is enough.
        pending = true;
        handler.postDelayed(report, 50);
    }

    /** Puts the snow's window up or takes it down, and only listens for windows while it is up. */
    void setSnowing(boolean snowing) {
        if (snowing && window == null) {
            OverlayWindow made = new OverlayWindow(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, true);
            if (!made.show()) {
                made.close();
                return;
            }
            window = made;
            Snow.setOverlay(window);
            listen(true);
            report();
        } else if (!snowing && window != null) {
            listen(false);
            handler.removeCallbacks(report);
            pending = false;
            Snow.clearOverlay(window);
            window.close();
            window = null;
        }
    }

    private void listen(boolean on) {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        info.eventTypes = on ? AccessibilityEvent.TYPE_WINDOWS_CHANGED | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED : 0;
        setServiceInfo(info);
    }

    /** Tells the snow which windows it can lie on, front to back. */
    private void report() {
        pending = false;
        if (window == null) return;
        List<AccessibilityWindowInfo> windows;
        try {
            windows = new ArrayList<>(getWindows());
        } catch (RuntimeException unavailable) {
            return;
        }
        Collections.sort(windows, (a, b) -> Integer.compare(b.getLayer(), a.getLayer()));
        Point screen = screen();
        float density = getResources().getDisplayMetrics().density;
        Rect bounds = new Rect();
        Rect divider = null;
        for (AccessibilityWindowInfo info : windows) {
            if (info.getType() == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER) {
                divider = new Rect();
                info.getBoundsInScreen(divider);
            }
        }
        float[] numbers = new float[windows.size() * Native.WINDOW_NUMBERS];
        int count = 0;
        for (AccessibilityWindowInfo info : windows) {
            info.getBoundsInScreen(bounds);
            int type = info.getType();
            float radius = 0f;
            int kind;
            if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                // The keyboard, docked or floating, not a strip of suggestions or a voice button on its own.
                if (bounds.width() < screen.x / 2 || bounds.height() < 120 * density || bounds.height() > screen.y * 0.75f || bounds.top < 48 * density) {
                    continue;
                }
                kind = Native.WINDOW_SETTLES | Native.WINDOW_PANEL;
            } else if (type == AccessibilityWindowInfo.TYPE_APPLICATION && info.isInPictureInPictureMode()) {
                kind = Native.WINDOW_SETTLES;
                if (Build.VERSION.SDK_INT >= 31) radius = 14 * density;
            } else if (type == AccessibilityWindowInfo.TYPE_APPLICATION
                    && divider != null
                    && Math.abs(bounds.top - divider.bottom) <= 6 * density
                    && bounds.width() >= screen.x * 0.9f
                    && bounds.height() < screen.y * 0.9f) {
                // The lower half of a screen split top and bottom: its top edge is a ledge like any window's.
                kind = Native.WINDOW_SETTLES;
            } else {
                continue;
            }
            int at = count * Native.WINDOW_NUMBERS;
            numbers[at] = (info.getId() & 0x3FFFFF) + 1;
            numbers[at + 1] = bounds.left;
            numbers[at + 2] = bounds.top;
            numbers[at + 3] = bounds.right;
            numbers[at + 4] = bounds.bottom;
            numbers[at + 5] = radius;
            numbers[at + 6] = kind;
            count++;
        }
        window.engine().setSystemWindows(numbers, count);
    }

    /** The whole screen's size in pixels, bars and all, as it is turned now. */
    @SuppressWarnings("deprecation")
    private Point screen() {
        WindowManager manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        Point size = new Point();
        if (Build.VERSION.SDK_INT >= 30) {
            Rect bounds = manager.getMaximumWindowMetrics().getBounds();
            size.set(bounds.width(), bounds.height());
        } else {
            Display display = manager.getDefaultDisplay();
            display.getRealSize(size);
        }
        return size;
    }
}
