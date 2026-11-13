package app.snowhush.android;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Deep Interaction. The desktop apps ask their systems where the windows are, and the snow settles on them. A
 * phone's home screen has widgets and icons instead, and the one thing Android tells where they are is an
 * accessibility service. This one reads how the home screen is laid out, and nothing else: it only listens to the
 * launcher, only while the live wallpaper is in sight, and the app has no way to send anything anywhere.
 *
 * <p>The layout says which items there are and roughly where. Exactly where each begins, the snow's ledge, comes
 * from a picture of the home screen's own layer, which Android 14 and later hand over: see {@link HomePicture} and
 * {@link Outline}. Without one, on an older Android, the picture in an icon is guessed at from how launchers build
 * them, and two sliders in the app move the guess.
 *
 * <p>What it finds goes to the wallpaper as the snow's windows. An item out of sight for now, on another page or
 * behind the app drawer, keeps its snow for when it is back, as a window on another desktop does.
 */
public final class DeepService extends AccessibilityService implements SharedPreferences.OnSharedPreferenceChangeListener {
    /** How long a picture of the home screen is waited for, and how long one is gone by before another is taken. */
    private static final long PICTURE_WAIT = 1200;
    private static final long PICTURE_KEPT = 20_000;
    /** The least Android leaves between two pictures, and a little more. */
    private static final long PICTURE_REST = 420;
    /** How long the home screen is left to settle before it is read, in milliseconds: after a change, after a slide. */
    private static final long SETTLE = 220;
    private static final long SETTLE_SLIDE = 380;
    /** The least between two reads set off by a widget redrawing itself. */
    private static final long REST = 1500;
    /** The most items remembered: a few pages' worth. */
    private static final int KNOWN = 160;
    private static final int MOST_NODES = 4000;

    private static DeepService running;
    private static volatile String report = "";
    /** Whether the icons' places are guessed at, for want of a picture to find them in. */
    private static volatile boolean guessing = Build.VERSION.SDK_INT < 34;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable read = this::read;
    private HandlerThread thread;
    private Handler reader;
    private SharedPreferences prefs;
    private String home;
    private boolean sliding;

    // The reading thread's.
    private final LinkedHashMap<Integer, float[]> known = new LinkedHashMap<>(64, 0.75f, true);
    /** Where a picture showed each item to be, by the item: left, top, right, bottom, radius and curve, or null for nothing. */
    private final HashMap<Integer, float[]> fits = new HashMap<>();
    private long readAt;
    private long pictureAt;
    private int nodes;
    /** Counts the looks, so an answer that comes late knows a newer look has taken over. */
    private int turn;
    /** Whether Android's pictures of the home screen are any use here: until one comes back solid all over. */
    private boolean pictures = Build.VERSION.SDK_INT >= 34;
    private int refusals;
    private String pictured = "";

    /** The service while Android has it running, or null. */
    static DeepService running() {
        return running;
    }

    /** What the last look at the home screen found, without a word of what it shows: for fitting a launcher. */
    /** Whether where the icons are drawn is a guess, which the app's two sliders can then set right. */
    static boolean guessing() {
        return guessing;
    }

    static String report() {
        return report;
    }

    @Override
    protected void onServiceConnected() {
        prefs = Prefs.of(this);
        prefs.registerOnSharedPreferenceChangeListener(this);
        thread = new HandlerThread("layout");
        thread.start();
        reader = new Handler(thread.getLooper());
        // Only the launcher is listened to: what any other app does never reaches the service.
        narrow();
        running = this;
        Snow.changed();
        look();
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

    private void stop() {
        if (running != this) return;
        running = null;
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        thread.quitSafely();
        // With no one to say where they are, the widgets and icons hold no snow: it falls.
        Snow.setItems(new float[0], 0, false);
    }

    @Override
    public void onInterrupt() {}

    /** The app that shows the home screen. */
    private String launcher() {
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo found = getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        String name = found == null || found.activityInfo == null ? null : found.activityInfo.packageName;
        // With several launchers and none chosen, Android answers with its chooser.
        return name == null || "android".equals(name) ? null : name;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // With no launcher known to be the home screen's, nothing is listened to and nothing read.
        if (home == null || !Snow.anyWallpaperInSight()) return;
        CharSequence from = event.getPackageName();
        if (from == null || !home.contentEquals(from)) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            // The pages are sliding: nothing is where it was read. Its snow waits until they have come to rest.
            if (!sliding) {
                sliding = true;
                Snow.setItems(Snow.items(), Snow.itemCount(), true);
            }
            schedule(SETTLE_SLIDE);
        } else if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // A widget rewriting its words moves nothing.
            int changes = event.getContentChangeTypes();
            int words = AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT | AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION;
            if (changes != 0 && (changes & ~words) == 0) return;
            schedule(Math.max(SETTLE, REST - (SystemClock.uptimeMillis() - readAt)));
        } else {
            schedule(SETTLE);
        }
    }

    /** Reads the home screen soon: the wallpaper came into sight, or what the snow lies on changed. */
    void look() {
        if (reader == null) return;
        if (home == null) narrow();
        if (home != null) schedule(SETTLE);
    }

    /** Finds out which app shows the home screen, and listens to that one alone. */
    private void narrow() {
        home = launcher();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        // Until it is known, a name no app has: nothing at all is heard.
        info.packageNames = new String[] {home == null ? "none.known" : home};
        setServiceInfo(info);
    }

    private void schedule(long delay) {
        reader.removeCallbacks(read);
        reader.postDelayed(read, Math.max(0, delay));
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences changed, String key) {
        if (Prefs.WIDGETS.equals(key) || Prefs.ICONS.equals(key) || Prefs.ICON_LIFT.equals(key) || Prefs.ICON_WIDTH.equals(key)) {
            reader.post(known::clear);
            look();
        }
    }

    /** Something on the home screen that holds snow: what the snow is told of it, where its room is, and what it is. */
    private static final class Item {
        final float[] numbers;
        final Rect room;
        final boolean icon;

        Item(float[] numbers, Rect room, boolean icon) {
            this.numbers = numbers;
            this.room = room;
            this.icon = icon;
        }

        int id() {
            return (int) numbers[0];
        }
    }

    /** Looks the home screen over, on the reading thread, and hands what it found to the main one. */
    private void read() {
        readAt = SystemClock.uptimeMillis();
        final int look = ++turn;
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (RuntimeException unavailable) {
            root = null;
        }
        // Something else is in front, the lock screen or the notifications: what was found last stands.
        if (home == null || root == null || root.getPackageName() == null || !home.contentEquals(root.getPackageName())) {
            main.post(() -> {
                if (running == this && sliding) {
                    sliding = false;
                    Snow.setItems(Snow.items(), Snow.itemCount(), false);
                }
            });
            return;
        }
        final DisplayMetrics metrics = getResources().getDisplayMetrics();
        final ArrayList<Item> seen = new ArrayList<>();
        final StringBuilder notes = new StringBuilder();
        final Rect window = new Rect();
        int windowId = -1;
        nodes = 0;
        try {
            root.getBoundsInScreen(window);
            windowId = root.getWindowId();
            collect(root, 0, metrics, seen, notes, false);
            if (seen.isEmpty()) {
                // A launcher that lets on nothing of what can be held down: an icon is what can be tapped and has a name.
                nodes = 0;
                notes.setLength(0);
                collect(root, 0, metrics, seen, notes, true);
            }
            if (seen.isEmpty()) {
                // Nothing to go by: how the screen is built, for whoever fits this launcher.
                nodes = 0;
                outline(root, 0, notes);
            }
        } catch (RuntimeException gone) {
            // The screen changed under the reading: the next look gets it whole.
        }
        final String launcher = root.getPackageName().toString();
        final int read = nodes;
        boolean stale = readAt - pictureAt > PICTURE_KEPT || seen.isEmpty();
        for (Item item : seen) stale |= !fits.containsKey(item.id());
        if (!pictures || !stale || window.isEmpty() || windowId < 0) {
            tell(launcher, metrics, read, seen, notes);
            return;
        }
        // Exactly where each item begins is in a picture of the home screen's own layer. Android takes its time over
        // one, and now and then never answers: past a wait the guesses are told, and set right if the picture
        // comes after all.
        final boolean[] told = {false};
        reader.postDelayed(() -> {
            if (turn != look || told[0]) return;
            told[0] = true;
            pictured = "no picture came";
            tell(launcher, metrics, read, seen, notes);
        }, PICTURE_WAIT);
        HomePicture.take(this, windowId, reader::post, (picture, error) -> {
            // A newer look has taken over.
            if (turn != look) return;
            if (picture == null && error == HomePicture.TOO_SOON) {
                // What shows stays as it is until Android will take another.
                if (!told[0]) schedule(PICTURE_REST);
                return;
            }
            if (picture == null) {
                pictured = "no picture, " + error;
                if (++refusals >= 3) pictures = false;
                if (told[0]) return;
            } else {
                refusals = 0;
                if (!fit(picture, window, metrics, seen, notes) && told[0]) return;
            }
            told[0] = true;
            tell(launcher, metrics, read, seen, notes);
        });
    }

    /**
     * Finds in a picture of the home screen's own layer where each item begins, and remembers it. With no item told
     * of by the launcher, whatever the picture shows that could be one is an item. Returns whether the picture was
     * any use.
     */
    private boolean fit(Outline.Picture picture, Rect window, DisplayMetrics metrics, ArrayList<Item> seen, StringBuilder notes) {
        float share = Outline.solidShare(picture);
        if (share > 0.9f) {
            // Solid all over: no layer of its own but a whole screen, wallpaper and all. Nothing to be made out, ever.
            pictures = false;
            pictured = "pictures are solid";
            return false;
        }
        if (share < 0.002f) {
            // Nothing drawn at all: the home screen is on its way in or out.
            pictured = "an empty picture";
            return false;
        }
        pictureAt = SystemClock.uptimeMillis();
        pictured = "";
        if (fits.size() > 600) fits.clear();
        // The picture is the window's, and may be drawn at another size than the window is shown at.
        float across = picture.width / (float) window.width();
        float down = picture.height / (float) window.height();
        float dp = metrics.density * across;
        if (seen.isEmpty()) {
            for (int[] thing : Outline.things(picture, dp)) {
                float[] shape = Outline.within(picture, thing[0], thing[1], thing[2], thing[3], dp);
                if (shape == null) continue;
                Rect room = new Rect(
                        window.left + Math.round(thing[0] / across),
                        window.top + Math.round(thing[1] / down),
                        window.left + Math.round(thing[2] / across),
                        window.top + Math.round(thing[3] / down));
                boolean icon = room.width() <= 130 * metrics.density && room.height() <= 150 * metrics.density;
                if (icon ? !prefs.getBoolean(Prefs.ICONS, true) : !prefs.getBoolean(Prefs.WIDGETS, true)) continue;
                float[] numbers = new float[Native.WINDOW_NUMBERS];
                // No name to know it by: where it sits, to within a few pixels.
                int grain = Math.max(1, Math.round(4 * metrics.density));
                numbers[0] = (Objects.hash(room.left / grain, room.top / grain, room.width() / grain, icon) & 0x3FFFFF) + 1;
                Item item = new Item(numbers, room, icon);
                fits.put(item.id(), onScreen(shape, window, across, down));
                seen.add(item);
            }
            notes.append("found by picture alone: ").append(seen.size()).append('\n');
            return true;
        }
        for (Item item : seen) {
            // A little past the item's room: an icon may reach to the very edge of it.
            int margin = Math.round(2 * metrics.density);
            float[] shape = Outline.within(
                    picture,
                    Math.round((item.room.left - window.left - margin) * across),
                    Math.round((item.room.top - window.top - margin) * down),
                    Math.round((item.room.right - window.left + margin) * across),
                    Math.round((item.room.bottom - window.top + margin) * down),
                    dp);
            fits.put(item.id(), shape == null ? null : onScreen(shape, window, across, down));
        }
        return true;
    }

    /** What {@link Outline} made out in the picture, in the screen's pixels. */
    private static float[] onScreen(float[] shape, Rect window, float across, float down) {
        return new float[] {
            window.left + shape[Outline.LEFT] / across,
            window.top + shape[Outline.TOP] / down,
            window.left + shape[Outline.RIGHT] / across,
            window.top + shape[Outline.BOTTOM] / down,
            shape[Outline.RADIUS] / across,
            shape[Outline.EXPONENT],
        };
    }

    /** Hands the wallpaper what the home screen holds: each item where a picture showed it, or where it was guessed. */
    private void tell(String launcher, DisplayMetrics metrics, int read, ArrayList<Item> seen, StringBuilder notes) {
        float density = metrics.density;
        ArrayList<float[]> shown = new ArrayList<>();
        int fitted = 0;
        for (Item item : seen) {
            float[] numbers = item.numbers;
            if (fits.containsKey(item.id())) {
                float[] fit = fits.get(item.id());
                // The launcher tells of it, and nothing is drawn there: nothing for snow to lie on.
                if (fit == null) continue;
                System.arraycopy(fit, 0, numbers, 1, 5);
                numbers[7] = fit[5];
                boolean small = item.icon || fit[2] - fit[0] < 84 * density || fit[3] - fit[1] < 44 * density;
                numbers[6] = Native.WINDOW_SETTLES | (small ? Native.WINDOW_SMALL : 0);
                fitted++;
            }
            if (item.icon) {
                // The person's own nudges, for a launcher that lays its icons out another way.
                float size = numbers[3] - numbers[1];
                float wider = Math.max(Prefs.iconWidth(prefs) * density, 24 * density - size);
                float lift = Prefs.iconLift(prefs) * density;
                numbers[1] -= wider / 2f;
                numbers[3] += wider / 2f;
                numbers[2] -= lift;
                numbers[4] -= lift;
            }
            shown.add(numbers);
        }
        // Every item known is told of: the ones in sight where they are, the others as away, with their snow kept.
        for (float[] item : known.values()) item[6] = Native.WINDOW_AWAY;
        for (float[] item : shown) known.put((int) item[0], item);
        Iterator<Map.Entry<Integer, float[]>> oldest = known.entrySet().iterator();
        while (known.size() > KNOWN && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
        final int count = known.size();
        final float[] numbers = new float[count * Native.WINDOW_NUMBERS];
        int at = 0;
        for (float[] item : known.values()) {
            System.arraycopy(item, 0, numbers, at, Native.WINDOW_NUMBERS);
            at += Native.WINDOW_NUMBERS;
        }
        String outlines = fitted > 0 ? fitted + " outlines from a picture" : pictured.isEmpty() ? "outlines guessed" : "outlines guessed, " + pictured;
        final boolean guessed = fitted == 0;
        StringBuilder places = new StringBuilder();
        for (float[] item : shown) {
            places.append("snow lies at ").append(Math.round(item[1])).append(',').append(Math.round(item[2])).append(' ')
                    .append(Math.round(item[3] - item[1])).append('x').append(Math.round(item[4] - item[2])).append(" corners ")
                    .append(Math.round(item[5])).append(" curve ").append(item[7]).append('\n');
        }
        final String noted = "launcher " + launcher + ", Android " + Build.VERSION.SDK_INT + ", " + metrics.widthPixels + "x" + metrics.heightPixels
                + " at " + density + ", " + read + " nodes, " + shown.size() + " in sight of " + count + ", " + outlines + "\n" + notes + places;
        main.post(() -> {
            if (running != this) return;
            sliding = false;
            if (!noted.equals(report)) {
                String[] lines = noted.split("\n");
                Log.i("Snowhush", "home screen: " + lines[0]);
                for (int line = 1; line < lines.length; line++) Log.d("Snowhush", lines[line]);
            }
            report = noted;
            if (!shown.isEmpty()) guessing = guessed;
            Snow.setItems(numbers, count, false);
        });
    }

    /**
     * Finds the home screen's items under {@code node}. A launcher lets every widget, icon and folder be held down
     * and dragged, so an item is whatever can be long-pressed and is neither tiny nor most of the screen.
     */
    private void collect(AccessibilityNodeInfo node, int depth, DisplayMetrics metrics, ArrayList<Item> seen, StringBuilder notes, boolean tapped) {
        if (node == null || depth > 40 || ++nodes > MOST_NODES || !node.isVisibleToUser()) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        float density = metrics.density;
        int width = bounds.width();
        int height = bounds.height();
        boolean icon = width <= 130 * density && height <= 150 * density;
        boolean held = node.isLongClickable()
                || (tapped && icon && node.isClickable() && (node.getContentDescription() != null || node.getText() != null));
        boolean item = held
                && width >= 28 * density
                && height >= 28 * density
                && width <= metrics.widthPixels * 0.96f
                && height <= metrics.heightPixels * 0.6f;
        if (!item) {
            for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), depth + 1, metrics, seen, notes, tapped);
            return;
        }
        if (icon ? !prefs.getBoolean(Prefs.ICONS, true) : !prefs.getBoolean(Prefs.WIDGETS, true)) return;
        float[] numbers = icon ? icon(node, bounds, density, notes) : widget(node, bounds, density, notes);
        CharSequence label = node.getContentDescription() != null ? node.getContentDescription() : node.getText();
        // The same item is the same window to the snow each time it is found: what it is called and where it sits.
        int hash = Objects.hash(label == null ? "" : label.toString(), width, height, bounds.left, bounds.top, icon);
        numbers[0] = (hash & 0x3FFFFF) + 1;
        seen.add(new Item(numbers, bounds, icon));
    }

    /** Notes how the screen is built, down to where it gets small: what each part is and where, and nothing it says. */
    private void outline(AccessibilityNodeInfo node, int depth, StringBuilder notes) {
        if (node == null || depth > 12 || ++nodes > 120 || !node.isVisibleToUser()) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        CharSequence kind = node.getClassName();
        String name = kind == null ? "?" : kind.toString();
        notes.append("part ").append(depth).append(' ').append(name.substring(name.lastIndexOf('.') + 1)).append(' ').append(bounds.toShortString())
                .append(node.isClickable() ? " taps" : "").append(node.isLongClickable() ? " holds" : "").append(node.isScrollable() ? " scrolls" : "")
                .append(" children ").append(node.getChildCount()).append('\n');
        for (int i = 0; i < node.getChildCount(); i++) outline(node.getChild(i), depth + 1, notes);
    }

    /**
     * An app's icon, or a folder's, as well as it can be told without a picture of it: the snow lies on the picture,
     * not on the name under it. A launcher that builds the item from parts says where the picture is; one that draws
     * both in a single view doesn't, and the picture is taken to be as big as launchers draw it, in the middle of the
     * item above its name.
     */
    private float[] icon(AccessibilityNodeInfo node, Rect bounds, float density, StringBuilder notes) {
        float size = 0f;
        float left = 0f;
        float top = 0f;
        Rect part = new Rect();
        for (int i = 0; i < node.getChildCount() && size == 0f; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            child.getBoundsInScreen(part);
            CharSequence kind = child.getClassName();
            float shape = part.width() / (float) Math.max(1, part.height());
            if (kind != null && kind.toString().endsWith("ImageView") && shape > 0.8f && shape < 1.25f && part.width() >= 28 * density && part.width() >= bounds.width() * 0.4f) {
                size = part.width();
                left = part.left;
                top = part.top;
            }
        }
        boolean told = size > 0f;
        if (!told) {
            boolean named = bounds.height() - bounds.width() > 12 * density || bounds.height() > bounds.width() * 1.12f;
            float name = named ? 24 * density : 0f;
            // A launcher draws its icons smaller the more of them it fits across.
            float room = Math.min(bounds.width() * 0.7f, bounds.height() - name - 4 * density);
            size = Math.max(28 * density, Math.min(room, 62 * density));
            top = bounds.top + Math.max(0f, (bounds.height() - size - name) / 2f);
            left = bounds.exactCenterX() - size / 2f;
        }
        notes.append("icon ").append(node.getClassName()).append(' ').append(bounds.toShortString()).append(told ? " picture " : " guessed ")
                .append(Math.round(left)).append(',').append(Math.round(top)).append(' ').append(Math.round(size)).append(" children ")
                .append(node.getChildCount()).append('\n');
        // Rounded as launchers round their icons, a little squarer than a circle's arc.
        return new float[] {0, left, top, left + size, top + size, size * 0.3f, Native.WINDOW_SETTLES | Native.WINDOW_SMALL, 2.6f};
    }

    /** A widget: the snow lies along the top of what it shows, which sits a little inside the room its launcher gives it. */
    private float[] widget(AccessibilityNodeInfo node, Rect bounds, float density, StringBuilder notes) {
        Rect shown = new Rect(bounds);
        AccessibilityNodeInfo inside = node;
        Rect part = new Rect();
        for (int level = 0; level < 3 && inside.getChildCount() == 1; level++) {
            AccessibilityNodeInfo child = inside.getChild(0);
            if (child == null) break;
            child.getBoundsInScreen(part);
            if (!shown.contains(part) || part.width() < shown.width() * 0.6f || part.height() < shown.height() * 0.5f) break;
            shown.set(part);
            inside = child;
        }
        float radius = Build.VERSION.SDK_INT >= 31 ? getResources().getDimension(android.R.dimen.system_app_widget_background_radius) : 16 * density;
        radius = Math.min(radius, Math.min(shown.width(), shown.height()) / 2f);
        notes.append("widget ").append(node.getClassName()).append(' ').append(bounds.toShortString()).append(" shown ").append(shown.toShortString())
                .append(" children ").append(node.getChildCount()).append('\n');
        return new float[] {0, shown.left, shown.top, shown.right, shown.bottom, radius, Native.WINDOW_SETTLES, 2.2f};
    }
}
