package app.snowhush.android;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** The snow over every app, on and off from Quick Settings, where the desktop apps have their menu bar and tray icons. */
public final class SnowTile extends TileService {
    private final Runnable watcher = this::update;

    @Override
    public void onStartListening() {
        Snow.watch(watcher);
        update();
    }

    @Override
    public void onStopListening() {
        Snow.unwatch(watcher);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onClick() {
        boolean snowing = !Prefs.snowing(this);
        if (snowing && !Snow.allowed(this)) {
            // Nothing may put the snow up yet: the app says what to allow.
            Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE));
            } else {
                startActivityAndCollapse(open);
            }
            return;
        }
        Prefs.setSnowing(this, snowing);
        Snow.apply(this);
        update();
    }

    private void update() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(Prefs.snowing(this) && Snow.allowed(this) ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.app_name));
        tile.updateTile();
    }
}
