package app.snowhush.android;

import android.app.WallpaperColors;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

import java.io.File;

/**
 * The snow behind the apps: a live wallpaper, on the home and lock screens. It needs no permission at all, the
 * finger sweeps the drift as a cursor does, and the home screen's pages sliding past drag the air along as a
 * desktop's spaces do. Behind it is the night under a street lamp, or a picture of the person's own.
 */
public final class SnowWallpaper extends WallpaperService {
    @Override
    public Engine onCreateEngine() {
        return new Host();
    }

    private final class Host extends Engine {
        private SnowEngine engine;
        private float along = 0.5f;
        private long alongAt;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(true);
            engine = new SnowEngine(SnowWallpaper.this, SnowEngine.WALLPAPER);
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            engine.surfaceChanged(holder.getSurface(), width, height, getResources().getDisplayMetrics().density);
            engine.setShown(isVisible());
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            engine.surfaceDestroyed();
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            engine.setShown(visible);
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xStep, float yStep, int xPixels, int yPixels) {
            long now = SystemClock.uptimeMillis();
            float velocity = 0f;
            long elapsed = now - alongAt;
            // One page is one screen's width of air. A launcher's first word, and a jump, are no slide.
            if (alongAt != 0 && elapsed > 0 && elapsed < 200 && xStep > 0f && xStep <= 1f) {
                float pages = (xOffset - along) / xStep;
                velocity = -pages * getDesiredMinimumWidthOrSurface() / (elapsed / 1000f);
            }
            along = xOffset;
            alongAt = now;
            engine.pages(Math.max(0f, Math.min(1f, xOffset)), velocity);
        }

        private float getDesiredMinimumWidthOrSurface() {
            SurfaceHolder holder = getSurfaceHolder();
            int width = holder == null || holder.getSurfaceFrame() == null ? 0 : holder.getSurfaceFrame().width();
            return width > 0 ? width : getResources().getDisplayMetrics().widthPixels;
        }

        @Override
        public void onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            boolean down = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_POINTER_DOWN;
            engine.pointer(event.getX(), event.getY(), down);
        }

        @Override
        public WallpaperColors onComputeColors() {
            // What Android colours its own screens after: the picture's colours, or the night's.
            if (Prefs.of(SnowWallpaper.this).getInt(Prefs.PICTURE, 0) != 0) {
                try {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = 8;
                    Bitmap small = BitmapFactory.decodeFile(new File(getFilesDir(), Prefs.PICTURE_FILE).getPath(), options);
                    if (small != null) {
                        WallpaperColors colors = WallpaperColors.fromBitmap(small);
                        small.recycle();
                        return colors;
                    }
                } catch (RuntimeException | OutOfMemoryError unreadable) {
                    // The night's will do.
                }
            }
            return new WallpaperColors(Color.valueOf(0xFF17130F), Color.valueOf(0xFF221D18), Color.valueOf(0xFFFFD696));
        }

        @Override
        public void onDestroy() {
            engine.release();
            super.onDestroy();
        }
    }
}
