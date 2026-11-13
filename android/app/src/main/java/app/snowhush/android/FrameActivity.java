package app.snowhush.android;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Frames the wallpaper's picture: the whole screen shows it as the wallpaper will, a finger moves it, two fingers
 * make it bigger or smaller, and it never leaves any of the screen bare. What the screen shows when it is accepted
 * is what the wallpaper is given, and how it was framed is remembered for the next time.
 */
public final class FrameActivity extends Activity {
    private static final String TAG = "Snowhush";
    /** How much bigger than filling the screen the picture can be made. */
    private static final float CLOSEST = 8f;

    private SharedPreferences prefs;
    private Bitmap picture;
    private Framed framed;

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = Prefs.of(this);
        // Behind the bars, as the wallpaper is.
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        if (Build.VERSION.SDK_INT < 35) {
            getWindow().setStatusBarColor(0x00000000);
            getWindow().setNavigationBarColor(0x00000000);
        }
        try {
            picture = BitmapFactory.decodeFile(new File(getFilesDir(), Prefs.PICTURE_SOURCE).getPath());
        } catch (RuntimeException | OutOfMemoryError unreadable) {
            picture = null;
        }
        if (picture == null) {
            Toast.makeText(this, R.string.picture_failed, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Ui ui = new Ui(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.NIGHT);
        framed = new Framed(this);
        root.addView(framed, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout buttons = ui.row();
        buttons.setBackground(ui.rounded(0xCC17130F, 10, 0));
        buttons.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
        TextView cancel = ui.button(getString(android.R.string.cancel), false);
        cancel.setOnClickListener(view -> finish());
        TextView center = ui.button(getString(R.string.wallpaper_center), false);
        center.setOnClickListener(view -> framed.center());
        TextView accept = ui.button(getString(android.R.string.ok), true);
        accept.setOnClickListener(view -> accept());
        buttons.addView(cancel, ui.weighted());
        LinearLayout.LayoutParams second = ui.weighted();
        second.setMarginStart(ui.dp(8));
        buttons.addView(center, second);
        LinearLayout.LayoutParams third = ui.weighted();
        third.setMarginStart(ui.dp(8));
        buttons.addView(accept, third);
        final FrameLayout.LayoutParams low = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        final int margin = ui.dp(16);
        low.setMargins(margin, margin, margin, margin);
        root.addView(buttons, low);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            low.setMargins(margin + left, margin, margin + right, margin + bottom);
            buttons.setLayoutParams(low);
            return insets;
        });
        setContentView(root);
    }

    /** Keeps what the screen shows as the wallpaper's picture. */
    private void accept() {
        final int width = framed.getWidth();
        final int height = framed.getHeight();
        if (width <= 0 || height <= 0) return;
        final Matrix placed = new Matrix(framed.placed);
        final float zoom = framed.zoom();
        final float[] middle = framed.middle();
        new Thread(() -> {
            boolean kept = false;
            try {
                Bitmap shown = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                new Canvas(shown).drawBitmap(picture, placed, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
                File draft = new File(getFilesDir(), Prefs.PICTURE_FILE + ".new");
                try (FileOutputStream out = new FileOutputStream(draft)) {
                    kept = shown.compress(Bitmap.CompressFormat.JPEG, 94, out);
                }
                shown.recycle();
                kept = kept && draft.renameTo(new File(getFilesDir(), Prefs.PICTURE_FILE));
            } catch (IOException | RuntimeException | OutOfMemoryError failed) {
                Log.w(TAG, "the picture can't be kept", failed);
            }
            final boolean done = kept;
            runOnUiThread(() -> {
                if (done) {
                    prefs.edit()
                            .putFloat(Prefs.PICTURE_ZOOM, zoom)
                            .putFloat(Prefs.PICTURE_X, middle[0])
                            .putFloat(Prefs.PICTURE_Y, middle[1])
                            .putInt(Prefs.PICTURE, Math.max(1, prefs.getInt(Prefs.PICTURE, 0) + 1))
                            .apply();
                } else {
                    Toast.makeText(this, R.string.picture_failed, Toast.LENGTH_LONG).show();
                }
                finish();
            });
        }, "picture").start();
    }

    /** The picture on the screen, where the fingers put it. */
    private final class Framed extends View {
        final Matrix placed = new Matrix();
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final ScaleGestureDetector pinch;
        private final GestureDetector taps;
        /** The size at which the picture just fills the screen, and how many times that it is shown. */
        private float fill = 1f;
        private float zoom = 1f;
        /** The point of the picture at the middle of the screen, as shares of its width and height. */
        private float x = 0.5f;
        private float y = 0.5f;
        private float lastX;
        private float lastY;
        private int finger = -1;

        Framed(Context context) {
            super(context);
            zoom = Math.max(1f, Math.min(CLOSEST, prefs.getFloat(Prefs.PICTURE_ZOOM, 1f)));
            x = prefs.getFloat(Prefs.PICTURE_X, 0.5f);
            y = prefs.getFloat(Prefs.PICTURE_Y, 0.5f);
            pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    // What is under the fingers stays under them.
                    float before = fill * zoom;
                    float px = x * picture.getWidth() + (detector.getFocusX() - getWidth() / 2f) / before;
                    float py = y * picture.getHeight() + (detector.getFocusY() - getHeight() / 2f) / before;
                    zoom = Math.max(1f, Math.min(CLOSEST, zoom * detector.getScaleFactor()));
                    float after = fill * zoom;
                    x = (px - (detector.getFocusX() - getWidth() / 2f) / after) / picture.getWidth();
                    y = (py - (detector.getFocusY() - getHeight() / 2f) / after) / picture.getHeight();
                    place();
                    return true;
                }
            });
            taps = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent event) {
                    center();
                    return true;
                }
            });
        }

        float zoom() {
            return zoom;
        }

        float[] middle() {
            return new float[] {x, y};
        }

        /** Back to the whole picture, in the middle. */
        void center() {
            zoom = 1f;
            x = 0.5f;
            y = 0.5f;
            place();
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            fill = Math.max(width / (float) picture.getWidth(), height / (float) picture.getHeight());
            place();
        }

        /** Works out where the picture is drawn, with none of the screen left bare. */
        private void place() {
            if (getWidth() == 0 || getHeight() == 0) return;
            float scale = fill * zoom;
            float halfAcross = getWidth() / 2f / scale / picture.getWidth();
            float halfDown = getHeight() / 2f / scale / picture.getHeight();
            x = halfAcross >= 0.5f ? 0.5f : Math.max(halfAcross, Math.min(1f - halfAcross, x));
            y = halfDown >= 0.5f ? 0.5f : Math.max(halfDown, Math.min(1f - halfDown, y));
            placed.reset();
            placed.postTranslate(-x * picture.getWidth(), -y * picture.getHeight());
            placed.postScale(scale, scale);
            placed.postTranslate(getWidth() / 2f, getHeight() / 2f);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawBitmap(picture, placed, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            pinch.onTouchEvent(event);
            taps.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    finger = event.getPointerId(0);
                    lastX = event.getX();
                    lastY = event.getY();
                    break;
                case MotionEvent.ACTION_MOVE: {
                    int index = event.findPointerIndex(finger);
                    if (index < 0) break;
                    if (!pinch.isInProgress() && event.getPointerCount() == 1) {
                        float scale = fill * zoom;
                        x -= (event.getX(index) - lastX) / scale / picture.getWidth();
                        y -= (event.getY(index) - lastY) / scale / picture.getHeight();
                        place();
                    }
                    lastX = event.getX(index);
                    lastY = event.getY(index);
                    break;
                }
                case MotionEvent.ACTION_POINTER_UP: {
                    // The finger that stays carries on from where it is.
                    int lifted = event.getActionIndex();
                    if (event.getPointerId(lifted) == finger) {
                        int other = lifted == 0 ? 1 : 0;
                        finger = event.getPointerId(other);
                        lastX = event.getX(other);
                        lastY = event.getY(other);
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    finger = -1;
                    break;
                default:
                    break;
            }
            return true;
        }
    }
}
