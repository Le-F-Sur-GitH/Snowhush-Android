package app.snowhush.android;

import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.Context;

import java.util.ArrayList;

/**
 * What the app's parts tell one another, all on the main thread: where the home screen's widgets and icons are, from
 * Deep Interaction to the live wallpaper, whose snow lies on them, and that either of the two came or went, to the
 * app's screen.
 */
final class Snow {
    /** Something that wants to hear when the home screen's layout, or what is running, changes. */
    interface Watcher {
        void onChanged();
    }

    private static final ArrayList<Watcher> watchers = new ArrayList<>();
    private static final ArrayList<SnowEngine> wallpapers = new ArrayList<>();
    private static float[] items = new float[0];
    private static int itemCount;
    private static boolean sliding;
    private static int wallpapersInSight;

    private Snow() {}

    static void watch(Watcher watcher) {
        if (!watchers.contains(watcher)) watchers.add(watcher);
    }

    static void unwatch(Watcher watcher) {
        watchers.remove(watcher);
    }

    static void changed() {
        for (Watcher watcher : new ArrayList<>(watchers)) watcher.onChanged();
    }

    /** A live wallpaper's snow, for as long as it is the phone's wallpaper. */
    static void join(SnowEngine wallpaper) {
        if (!wallpapers.contains(wallpaper)) wallpapers.add(wallpaper);
    }

    static void leave(SnowEngine wallpaper) {
        wallpapers.remove(wallpaper);
    }

    /** Clears what lies on the home and lock screens. */
    static void clear() {
        for (SnowEngine wallpaper : wallpapers) wallpaper.clear();
    }

    /** Whether Deep Interaction is on: the accessibility service is running. */
    static boolean deep() {
        return DeepService.running() != null;
    }

    /** Whether the live wallpaper is the phone's wallpaper. */
    static boolean wallpaper(Context context) {
        try {
            WallpaperManager manager = WallpaperManager.getInstance(context);
            WallpaperInfo info = manager == null ? null : manager.getWallpaperInfo();
            return info != null && context.getPackageName().equals(info.getPackageName());
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    /**
     * The widgets and icons on the home screen, {@link Native#WINDOW_NUMBERS} numbers each, in the screen's pixels;
     * {@code sliding} while its pages slide past, when nothing is where it was read.
     */
    static void setItems(float[] numbers, int count, boolean nowSliding) {
        items = numbers;
        itemCount = count;
        sliding = nowSliding;
        changed();
    }

    static float[] items() {
        return items;
    }

    static int itemCount() {
        return itemCount;
    }

    static boolean sliding() {
        return sliding;
    }

    /** A live wallpaper came into sight or went out of it: the home screen is only read while one shows. */
    static void wallpaperInSight(boolean inSight) {
        wallpapersInSight = Math.max(0, wallpapersInSight + (inSight ? 1 : -1));
        DeepService deep = DeepService.running();
        if (inSight && deep != null) deep.look();
    }

    static boolean anyWallpaperInSight() {
        return wallpapersInSight > 0;
    }
}
