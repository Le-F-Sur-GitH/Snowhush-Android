package app.snowhush.android;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;

import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

/**
 * A picture of the home screen's own layer, which Android 14 and later hand an accessibility service: what the
 * launcher drew, its widgets and icons, and nothing of the wallpaper behind them. Only how opaque each pixel is gets
 * kept, long enough for {@link Outline} to find where the widgets and icons begin; no colour of it is ever read.
 */
final class HomePicture {
    /** Android took none because the last one was too short a while ago. */
    static final int TOO_SOON = AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT;
    /** Android refused before it tried, or what it handed over couldn't be read. */
    static final int REFUSED = -1;

    interface Taker {
        /** The picture, or null and why there is none. */
        void taken(Outline.Picture picture, int error);
    }

    private HomePicture() {}

    /** Asks for a picture of one window; the answer comes on {@code executor}, once. */
    static void take(AccessibilityService service, int window, Executor executor, Taker taker) {
        try {
            service.takeScreenshotOfWindow(window, executor, new AccessibilityService.TakeScreenshotCallback() {
                @Override
                public void onSuccess(AccessibilityService.ScreenshotResult result) {
                    Outline.Picture picture = null;
                    HardwareBuffer buffer = result.getHardwareBuffer();
                    try {
                        picture = opacity(buffer, result);
                    } catch (RuntimeException | OutOfMemoryError unreadable) {
                        // No picture, then.
                    } finally {
                        buffer.close();
                    }
                    taker.taken(picture, picture == null ? REFUSED : 0);
                }

                @Override
                public void onFailure(int error) {
                    taker.taken(null, error);
                }
            });
        } catch (RuntimeException refused) {
            executor.execute(() -> taker.taken(null, REFUSED));
        }
    }

    /** How opaque each pixel of the picture is, and nothing else of it. */
    private static Outline.Picture opacity(HardwareBuffer buffer, AccessibilityService.ScreenshotResult result) {
        Bitmap drawn = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
        if (drawn == null) return null;
        Bitmap readable = drawn.copy(Bitmap.Config.ARGB_8888, false);
        drawn.recycle();
        if (readable == null) return null;
        Bitmap mask = readable.extractAlpha();
        readable.recycle();
        if (mask == null) return null;
        try {
            int stride = mask.getRowBytes();
            ByteBuffer bytes = ByteBuffer.allocate(stride * mask.getHeight());
            mask.copyPixelsToBuffer(bytes);
            return new Outline.Picture(bytes.array(), stride, mask.getWidth(), mask.getHeight());
        } finally {
            mask.recycle();
        }
    }
}
