package app.snowhush.android;

/** A drawing surface without a display, from the engine built with its {@code host} feature: see run.sh. */
final class HostEgl {
    private HostEgl() {}

    /** Makes a context current on this thread. Returns what went wrong, or null. */
    static native String open(int width, int height);

    /** Writes what the scene last drew, over a dusk, as a PNG. Returns what went wrong, or null. */
    static native String save(long scene, String path);
}
