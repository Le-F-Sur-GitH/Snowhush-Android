package app.snowhush.android;

import android.app.KeyguardManager;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

import java.io.File;

/**
 * Where the snow falls on a phone: the live wallpaper, on the home and lock screens and nowhere else. It needs no
 * permission, the finger sweeps the drift as a cursor does, and the home screen's pages sliding past drag the air
 * along as a desktop's spaces do. With Deep Interaction the home screen's widgets and icons are its windows: the snow
 * settles on them, and goes on settling, unseen, while an app is in front. Behind it is the night under a street
 * lamp, or a picture of the person's own.
 */
public final class SnowWallpaper extends WallpaperService {
    @Override
    public Engine onCreateEngine() {
        return new Host();
    }

    private final class Host extends Engine implements Snow.Watcher {
        private SnowEngine engine;
        private float along = 0.5f;
        private long alongAt;
        private boolean inSight;
        private boolean locked;
        private boolean listening;

        private final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    engine.setAwake(false);
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    engine.setAwake(true);
                }
                // Locked, the home screen's widgets and icons aren't there for snow to lie on.
                locked = Intent.ACTION_USER_PRESENT.equals(action) ? false : keyguard();
                lay();
            }
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(true);
            engine = new SnowEngine(SnowWallpaper.this, SnowEngine.WALLPAPER);
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            engine.setAwake(power == null || power.isInteractive());
            // The one Android shows while a wallpaper is being picked is on no home screen.
            if (isPreview()) return;
            listening = true;
            locked = keyguard();
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(receiver, filter);
            }
            Snow.watch(this);
            Snow.join(engine);
            Snow.changed();
            lay();
        }

        private boolean keyguard() {
            KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            return keyguard != null && keyguard.isKeyguardLocked();
        }

        /** Whether this wallpaper is the home screen's: from Android 14 the lock screen can have one of its own. */
        private boolean home() {
            return Build.VERSION.SDK_INT < 34 || (getWallpaperFlags() & WallpaperManager.FLAG_SYSTEM) != 0;
        }

        @Override
        public void onChanged() {
            lay();
        }

        /**
         * Tells the snow of the home screen's widgets and icons. While the screen is locked or the pages slide, they
         * aren't where they were read: their snow waits for them.
         */
        private void lay() {
            if (!listening) return;
            int count = home() ? Snow.itemCount() : 0;
            float[] numbers = new float[count * Native.WINDOW_NUMBERS];
            System.arraycopy(Snow.items(), 0, numbers, 0, numbers.length);
            if (locked || Snow.sliding()) {
                for (int item = 0; item < count; item++) numbers[item * Native.WINDOW_NUMBERS + 6] = Native.WINDOW_AWAY;
            }
            engine.setSystemWindows(numbers, count);
        }

        private void sight(boolean visible) {
            engine.setShown(visible);
            if (!listening || visible == inSight) return;
            inSight = visible;
            if (visible) {
                locked = keyguard();
                lay();
            }
            Snow.wallpaperInSight(visible);
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            engine.surfaceChanged(holder.getSurface(), width, height, getResources().getDisplayMetrics().density);
            sight(isVisible());
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            engine.surfaceDestroyed();
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            sight(visible);
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xStep, float yStep, int xPixels, int yPixels) {
            long now = SystemClock.uptimeMillis();
            float velocity = 0f;
            long elapsed = now - alongAt;
            // One page is one screen's width of air. A launcher's first word, and a jump, are no slide.
            if (alongAt != 0 && elapsed > 0 && elapsed < 200 && xStep > 0f && xStep <= 1f) {
                float pages = (xOffset - along) / xStep;
                velocity = -pages * width() / (elapsed / 1000f);
            }
            along = xOffset;
            alongAt = now;
            engine.pages(Math.max(0f, Math.min(1f, xOffset)), velocity);
        }

        private float width() {
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
            if (listening) {
                listening = false;
                Snow.unwatch(this);
                Snow.leave(engine);
                try {
                    unregisterReceiver(receiver);
                } catch (IllegalArgumentException alreadyGone) {
                    // Nothing to undo.
                }
                if (inSight) Snow.wallpaperInSight(false);
                inSight = false;
                Snow.changed();
            }
            engine.release();
            super.onDestroy();
        }
    }
}
