package app.snowhush.android;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Display;
import android.view.Surface;

/**
 * What a phone has in place of a cursor and windows to shake: itself. Held at a tilt, the snowfall drifts toward the
 * lower side, as it would; shaken, the drift is blown away, as off a shaken window.
 */
final class Motion implements SensorEventListener {
    interface Listener {
        /** The wind a tilt blows, in points a second, to the right of the screen when positive. */
        void onWind(float wind);

        /** The phone was shaken, mostly to the right of the screen when {@code direction} is positive. */
        void onShake(float direction);
    }

    /** The wind of a phone lying on its side, in points a second. */
    private static final float WIND = 110f;
    /** A tilt this slight, in metres a second squared of gravity across the screen, is a hand that isn't quite level. */
    private static final float LEVEL = 0.7f;
    /** A jolt this much past gravity, in metres a second squared, is part of a shake. */
    private static final float JOLT = 13f;
    private static final int JOLTS = 3;
    private static final long SHAKE_WINDOW = 700;
    private static final long SHAKE_REST = 2500;

    private final SensorManager sensors;
    private final Sensor accelerometer;
    private final Display display;
    private final Handler handler;
    private final Listener listener;
    private boolean listening;
    private boolean leveled;
    private float across;
    private float wind;
    private int jolts;
    private float joltSign;
    private long firstJoltAt;
    private long shookAt;

    Motion(Context context, Handler handler, Listener listener) {
        sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        DisplayManager displays = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        display = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
        this.handler = handler;
        this.listener = listener;
    }

    void start() {
        if (listening || accelerometer == null) return;
        leveled = false;
        jolts = 0;
        listening = sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI, handler);
    }

    void stop() {
        if (!listening) return;
        listening = false;
        sensors.unregisterListener(this);
        if (wind != 0f) {
            wind = 0f;
            listener.onWind(0f);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!listening || event.values.length < 3) return;
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        // What the sensor calls right is the screen's right only while the phone is held as it was built.
        float right;
        switch (display == null ? Surface.ROTATION_0 : display.getRotation()) {
            case Surface.ROTATION_90:
                right = -y;
                break;
            case Surface.ROTATION_180:
                right = -x;
                break;
            case Surface.ROTATION_270:
                right = y;
                break;
            default:
                right = x;
                break;
        }
        // At rest the sensor reads what holds the phone up: with its right side lower, that points to the left.
        if (!leveled) {
            leveled = true;
            across = right;
        } else {
            across += (right - across) * 0.12f;
        }
        float lean = Math.abs(across) < LEVEL ? 0f : -(across - Math.copySign(LEVEL, across)) / SensorManager.GRAVITY_EARTH;
        float next = Math.max(-1f, Math.min(1f, lean)) * WIND;
        if (Math.abs(next - wind) >= 1.5f || (next == 0f && wind != 0f)) {
            wind = next;
            listener.onWind(wind);
        }

        long now = SystemClock.uptimeMillis();
        float strength = (float) Math.sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH;
        if (strength > JOLT && now - shookAt > SHAKE_REST) {
            if (jolts == 0 || now - firstJoltAt > SHAKE_WINDOW) {
                jolts = 0;
                firstJoltAt = now;
                joltSign = 0f;
            }
            jolts++;
            joltSign += right - across;
            if (jolts >= JOLTS) {
                jolts = 0;
                shookAt = now;
                listener.onShake(joltSign < 0f ? 1f : -1f);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
