package app.snowhush.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;

/**
 * Keeps the snow over every app while it is put up with "Display over other apps": Android only leaves an app's
 * window up for as long as something of the app's is running in plain sight, which is what the notification is for.
 */
public final class OverlayService extends Service {
    static final String ACTION_STOP = "app.snowhush.android.STOP";
    private static final String TAG = "Snowhush";
    private static final String CHANNEL = "snow";
    private static final int NOTIFICATION = 1;

    private OverlayWindow window;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean stop = intent != null && ACTION_STOP.equals(intent.getAction());
        if (stop) Prefs.setSnowing(this, false);
        // Started as a foreground service, it has to become one, even to stop right after.
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION, notification());
            }
        } catch (RuntimeException refused) {
            Log.w(TAG, "the snow may not stay up from here", refused);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (stop || !Prefs.snowing(this) || !Settings.canDrawOverlays(this) || Snow.deep()) {
            stopSelf();
            Snow.changed();
            return START_NOT_STICKY;
        }
        if (window == null) {
            OverlayWindow made = new OverlayWindow(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, false);
            if (!made.show()) {
                made.close();
                stopSelf();
                return START_NOT_STICKY;
            }
            window = made;
            Snow.setOverlay(window);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (window != null) {
            Snow.clearOverlay(window);
            window.close();
            window = null;
        }
        super.onDestroy();
    }

    private Notification notification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null && manager.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(
                this, 1, new Intent(this, OverlayService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_snowflake)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(null, getString(R.string.stop), stop).build());
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return builder.build();
    }
}
