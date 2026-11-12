package app.snowhush.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;

/**
 * The app's one screen: what the desktop apps keep in their menu, laid out as cards. It snows here too. The cards
 * are the snow's windows: it settles on their tops, slides off as they scroll, and the finger sweeps it as a cursor
 * does. While the snow falls over every app, that snow is the one this screen talks to; otherwise the screen has
 * snow of its own.
 */
public final class MainActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "Snowhush";
    private static final int PICK_PICTURE = 1;
    private static final int ASK_NOTIFICATIONS = 2;
    /** The cards' ids as windows, clear of the ones Android gives its own. */
    private static final int CARD_ID = 16_000_000;
    private static final String EXTRA_SNOW = "snow";
    private static final String SPONSOR = "https://github.com/sponsors/mbezruchko";
    /** The longest side a wallpaper's picture is kept at, in pixels. */
    private static final int PICTURE_SIDE = 2560;

    private SharedPreferences prefs;
    private Ui ui;
    private FrameLayout root;
    private ScrollView scroll;
    private LinearLayout content;
    private SurfaceView snowView;
    /** The snow over this screen alone, for while none falls over every app. */
    private SnowEngine preview;
    /** The snow this screen's cards and touches are told to: the one over every app, or its own. */
    private SnowEngine told;
    private final ArrayList<View> cards = new ArrayList<>();
    private boolean started;
    private boolean awaitingOverlay;
    private boolean askedNotifications;
    private int statusBar;

    private Switch overlaySwitch;
    private TextView overlayHint;
    private TextView overlayAllow;
    private TextView intensityValue;
    private SeekBar intensity;
    private TextView opacityValue;
    private SeekBar opacity;
    private View accumulate;
    private TextView groundChip;
    private TextView windowsChip;
    private final TextView[] modeChips = new TextView[4];
    private Switch mixedSwitch;
    private Switch halloweenSwitch;
    private View halloweenRow;
    private TextView clearButton;
    private View meltRow;
    private TextView meltTitle;
    private Switch meltSwitch;
    private TextView deepStatus;
    private Switch motionSwitch;
    private Switch fullScreenSwitch;
    private Switch performanceSwitch;
    private Switch bootSwitch;
    private TextView removePicture;

    private final Runnable watcher = this::overlayChanged;
    private final Runnable settled = () -> reportCards(false);
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (changed, key) -> refresh();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = Prefs.of(this);
        ui = new Ui(this);
        edgeToEdge();
        build();
        setContentView(root);
        if (Native.available()) preview = new SnowEngine(this, SnowEngine.PREVIEW);
        if (state != null) {
            awaitingOverlay = state.getBoolean("awaitingOverlay");
        } else {
            asked(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        asked(intent);
    }

    /**
     * The snow over every app can be asked for along with the app, as a shortcut or a script would:
     * {@code am start -n app.snowhush.android/.MainActivity --ez snow true}. It still needs what it needs allowed.
     */
    private void asked(Intent intent) {
        if (intent == null || !intent.hasExtra(EXTRA_SNOW)) return;
        boolean snowing = intent.getBooleanExtra(EXTRA_SNOW, false);
        if (snowing && !Snow.allowed(this)) return;
        Prefs.setSnowing(this, snowing);
        Snow.apply(this);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("awaitingOverlay", awaitingOverlay);
    }

    /** The screen reaches behind the bars, as the snow does. */
    @SuppressWarnings("deprecation")
    private void edgeToEdge() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
        } else {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        if (Build.VERSION.SDK_INT < 35) {
            window.setStatusBarColor(0x00000000);
            window.setNavigationBarColor(0x00000000);
        }
        if (Build.VERSION.SDK_INT >= 29) window.setNavigationBarContrastEnforced(false);
    }

    // The screen

    private void build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.NIGHT);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        content = ui.column();
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView name = ui.display(getString(R.string.app_name));
        content.addView(name, ui.fill());
        TextView tagline = ui.summary(getString(R.string.tagline));
        tagline.setTextSize(16);
        content.addView(tagline, ui.spaced(6));
        TextView hint = ui.summary(getString(R.string.hint));
        hint.setTextColor(Ui.FAINT);
        content.addView(hint, ui.spaced(4));

        if (!Native.available()) {
            LinearLayout card = card();
            TextView missing = ui.summary(getString(R.string.unavailable));
            missing.setPadding(0, ui.dp(12), 0, ui.dp(12));
            card.addView(missing, ui.fill());
        }
        buildOverlay();
        buildSnow();
        buildBehavior();
        buildDisplay();
        buildGeneral();

        snowView = new SurfaceView(this);
        snowView.setZOrderOnTop(true);
        snowView.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        snowView.getHolder().addCallback(this);
        snowView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        root.addView(snowView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            inset(insets);
            return insets;
        });
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            reportCards(true);
            scroll.removeCallbacks(settled);
            scroll.postDelayed(settled, 140);
        });
        content.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> reportCards(false));
    }

    private LinearLayout card() {
        LinearLayout card = ui.card();
        content.addView(card);
        cards.add(card);
        return card;
    }

    /** A row with a title, a line under it if there is one to say, and a switch; a tap anywhere on it flips the switch. */
    private Switch switchRow(LinearLayout card, TextView title, int summary) {
        LinearLayout row = ui.row();
        row.setMinimumHeight(ui.dp(56));
        row.setPadding(0, ui.dp(10), 0, ui.dp(10));
        LinearLayout words = ui.column();
        words.addView(title, ui.fill());
        if (summary != 0) words.addView(ui.summary(getString(summary)), ui.spaced(2));
        row.addView(words, ui.weighted());
        Switch toggle = ui.toggle();
        toggle.setClickable(false);
        toggle.setFocusable(false);
        toggle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(toggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setClickable(true);
        row.setFocusable(true);
        row.setTag(toggle);
        card.addView(row, ui.fill());
        return toggle;
    }

    /** What a tap on a switch's row does; the switch itself only shows what the settings say. */
    @SuppressWarnings("deprecation")
    private void onToggle(Switch toggle, Runnable action) {
        View row = (View) toggle.getParent();
        row.setOnClickListener(view -> {
            if (row.isEnabled()) action.run();
        });
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Switch.class.getName());
                info.setCheckable(true);
                info.setChecked(toggle.isChecked());
            }
        });
    }

    /** A row that leads somewhere: a title, a line under it, and what it stands at. */
    private TextView linkRow(LinearLayout card, int title, int summary, Runnable action) {
        LinearLayout row = ui.row();
        row.setMinimumHeight(ui.dp(56));
        row.setPadding(0, ui.dp(10), 0, ui.dp(10));
        LinearLayout words = ui.column();
        words.addView(ui.title(getString(title)), ui.fill());
        if (summary != 0) words.addView(ui.summary(getString(summary)), ui.spaced(2));
        row.addView(words, ui.weighted());
        TextView status = ui.value("›");
        status.setPadding(ui.dp(12), 0, 0, 0);
        row.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(view -> action.run());
        card.addView(row, ui.fill());
        return status;
    }

    private void buildOverlay() {
        LinearLayout card = card();
        overlaySwitch = switchRow(card, ui.title(getString(R.string.overlay)), R.string.overlay_summary);
        onToggle(overlaySwitch, () -> setSnowing(!Prefs.snowing(this)));
        overlayHint = ui.summary(getString(R.string.overlay_allow_hint));
        overlayHint.setTextColor(Ui.FAINT);
        card.addView(overlayHint, ui.spaced(2));
        overlayAllow = ui.button(getString(R.string.allow), true);
        overlayAllow.setOnClickListener(view -> askOverlay());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = ui.dp(12);
        params.bottomMargin = ui.dp(12);
        card.addView(overlayAllow, params);
    }

    private void buildSnow() {
        LinearLayout card = card();

        LinearLayout intensityLabel = ui.row();
        intensityLabel.addView(ui.kicker(getString(R.string.intensity)), ui.weighted());
        intensityValue = ui.value("");
        intensityLabel.addView(intensityValue);
        card.addView(intensityLabel, ui.spaced(14));
        intensity = ui.slider(0, 100);
        intensity.setContentDescription(getString(R.string.intensity));
        card.addView(intensity, ui.fill());
        intensity.setOnSeekBarChangeListener(new Slid() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) prefs.edit().putInt(Prefs.INTENSITY, progress).apply();
            }
        });

        LinearLayout opacityLabel = ui.row();
        opacityLabel.addView(ui.kicker(getString(R.string.opacity)), ui.weighted());
        opacityValue = ui.value("");
        opacityLabel.addView(opacityValue);
        card.addView(opacityLabel, ui.spaced(6));
        opacity = ui.slider(10, 100);
        opacity.setContentDescription(getString(R.string.opacity));
        card.addView(opacity, ui.fill());
        opacity.setOnSeekBarChangeListener(new Slid() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) prefs.edit().putInt(Prefs.OPACITY, progress).apply();
            }
        });

        LinearLayout settle = ui.column();
        settle.addView(ui.kicker(getString(R.string.accumulate)), ui.spaced(8));
        LinearLayout surfaces = ui.row();
        groundChip = ui.chip(getString(R.string.surface_ground));
        groundChip.setOnClickListener(view -> flip(Prefs.GROUND, true));
        windowsChip = ui.chip(getString(R.string.surface_windows));
        windowsChip.setOnClickListener(view -> flip(Prefs.WINDOWS, true));
        surfaces.addView(groundChip, ui.weighted());
        LinearLayout.LayoutParams second = ui.weighted();
        second.setMarginStart(ui.dp(10));
        surfaces.addView(windowsChip, second);
        settle.addView(surfaces, ui.spaced(10));
        accumulate = settle;
        card.addView(settle, ui.fill());

        card.addView(ui.kicker(getString(R.string.mode)), ui.spaced(20));
        int[] names = {R.string.snow, R.string.autumn, R.string.rain, R.string.blossom};
        LinearLayout line = null;
        for (int mode = 0; mode < names.length; mode++) {
            if (mode % 2 == 0) {
                line = ui.row();
                card.addView(line, ui.spaced(10));
            }
            final int picked = mode;
            TextView chip = ui.chip(getString(names[mode]));
            chip.setOnClickListener(view -> Prefs.pick(prefs, picked));
            LinearLayout.LayoutParams params = ui.weighted();
            if (mode % 2 == 1) params.setMarginStart(ui.dp(10));
            line.addView(chip, params);
            modeChips[mode] = chip;
        }
        mixedSwitch = switchRow(card, ui.title(getString(R.string.mixed)), 0);
        ((View) mixedSwitch.getParent()).setPadding(0, ui.dp(14), 0, ui.dp(4));
        onToggle(mixedSwitch, () -> Prefs.toggleMixed(prefs));
        halloweenSwitch = switchRow(card, ui.title(getString(R.string.halloween)), 0);
        halloweenRow = (View) halloweenSwitch.getParent();
        halloweenRow.setPadding(0, ui.dp(4), 0, ui.dp(4));
        onToggle(halloweenSwitch, () -> Prefs.toggleHalloween(prefs, Calendar.getInstance()));

        clearButton = ui.button(getString(R.string.clear_snow), false);
        clearButton.setOnClickListener(view -> {
            if (told != null) told.clear();
        });
        LinearLayout.LayoutParams params = ui.spaced(12);
        params.bottomMargin = ui.dp(12);
        card.addView(clearButton, params);
    }

    private void buildBehavior() {
        LinearLayout card = card();
        card.addView(ui.kicker(getString(R.string.behavior)), ui.spaced(14));
        meltTitle = ui.title(getString(R.string.melt_snow));
        meltSwitch = switchRow(card, meltTitle, R.string.melt_summary);
        meltRow = (View) meltSwitch.getParent();
        onToggle(meltSwitch, () -> flip(Prefs.MELT, true));
        card.addView(ui.rule());
        deepStatus = linkRow(card, R.string.deep, R.string.deep_summary, this::openDeep);
        card.addView(ui.rule());
        motionSwitch = switchRow(card, ui.title(getString(R.string.motion)), R.string.motion_summary);
        onToggle(motionSwitch, () -> flip(Prefs.MOTION, true));
    }

    private void buildDisplay() {
        LinearLayout card = card();
        card.addView(ui.kicker(getString(R.string.display)), ui.spaced(14));
        fullScreenSwitch = switchRow(card, ui.title(getString(R.string.full_screen)), R.string.full_screen_summary);
        onToggle(fullScreenSwitch, () -> flip(Prefs.OVER_FULL_SCREEN, false));
        card.addView(ui.rule());
        performanceSwitch = switchRow(card, ui.title(getString(R.string.performance)), R.string.performance_summary);
        onToggle(performanceSwitch, () -> flip(Prefs.PERFORMANCE, false));
        card.addView(ui.rule());
        linkRow(card, R.string.wallpaper, R.string.wallpaper_summary, this::setWallpaper);
        LinearLayout pictures = ui.row();
        TextView choose = ui.button(getString(R.string.wallpaper_picture), false);
        choose.setOnClickListener(view -> choosePicture());
        pictures.addView(choose, ui.weighted());
        removePicture = ui.button(getString(R.string.wallpaper_remove), false);
        removePicture.setOnClickListener(view -> removePicture());
        LinearLayout.LayoutParams second = ui.weighted();
        second.setMarginStart(ui.dp(10));
        pictures.addView(removePicture, second);
        LinearLayout.LayoutParams params = ui.spaced(4);
        params.bottomMargin = ui.dp(12);
        card.addView(pictures, params);
    }

    private void buildGeneral() {
        LinearLayout card = card();
        card.addView(ui.kicker(getString(R.string.general)), ui.spaced(14));
        bootSwitch = switchRow(card, ui.title(getString(R.string.startup)), 0);
        onToggle(bootSwitch, () -> flip(Prefs.BOOT, false));
        card.addView(ui.rule());
        linkRow(card, R.string.support, 0, () -> open(new Intent(Intent.ACTION_VIEW, Uri.parse(SPONSOR))));
        card.addView(ui.rule());
        TextView about = ui.summary(getString(R.string.about, version()));
        about.setTextColor(Ui.FAINT);
        about.setPadding(0, ui.dp(14), 0, ui.dp(14));
        card.addView(about, ui.fill());
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException impossible) {
            return "";
        }
    }

    private void flip(String key, boolean fallback) {
        prefs.edit().putBoolean(key, !prefs.getBoolean(key, fallback)).apply();
    }

    /** Shows what the settings say. */
    private void refresh() {
        if (overlaySwitch == null) return;
        boolean snowing = Prefs.snowing(this) && Snow.allowed(this);
        boolean allowed = Snow.allowed(this);
        overlaySwitch.setChecked(snowing);
        ui.show(overlayHint, !allowed);
        ui.show(overlayAllow, !allowed);

        int amount = Prefs.intensity(prefs, Prefs.calm(this));
        intensity.setProgress(amount);
        intensityValue.setText(amount == 0 ? getString(R.string.off) : getString(R.string.percent, amount));
        int strength = Prefs.opacity(prefs);
        opacity.setProgress(strength);
        opacityValue.setText(getString(R.string.percent, strength));

        int[] modes = Prefs.modes(prefs);
        boolean snow = Prefs.falls(modes, Prefs.SNOW);
        boolean autumn = Prefs.falls(modes, Prefs.AUTUMN);
        boolean blossom = Prefs.falls(modes, Prefs.BLOSSOM);
        // Rain doesn't lie: with nothing but rain falling there is nothing to settle, melt or clear.
        boolean lies = snow || autumn || blossom;
        boolean ground = prefs.getBoolean(Prefs.GROUND, true);
        boolean windows = prefs.getBoolean(Prefs.WINDOWS, true);
        for (int mode = 0; mode < modeChips.length; mode++) ui.pick(modeChips[mode], Prefs.falls(modes, mode));
        mixedSwitch.setChecked(prefs.getBoolean(Prefs.MIXED, false));
        // Halloween's ghosts come out over snow falling alone.
        boolean haunts = Prefs.snowAlone(modes);
        halloweenSwitch.setChecked(haunts && Prefs.halloweenWanted(prefs, Calendar.getInstance()));
        ui.enable(halloweenRow, haunts);
        ui.show(accumulate, lies);
        ui.pick(groundChip, ground);
        ui.pick(windowsChip, windows);

        ui.show(clearButton, lies);
        clearButton.setText(clearLabel(snow, autumn, blossom));
        ui.show(meltRow, lies);
        meltTitle.setText(snow ? R.string.melt_snow : autumn ? R.string.melt_autumn : R.string.melt_blossom);
        meltSwitch.setChecked(prefs.getBoolean(Prefs.MELT, true));
        ui.enable(meltRow, ground || windows);

        deepStatus.setText(Snow.deep() ? getString(R.string.on) : "›");
        motionSwitch.setChecked(prefs.getBoolean(Prefs.MOTION, true));
        fullScreenSwitch.setChecked(prefs.getBoolean(Prefs.OVER_FULL_SCREEN, false));
        performanceSwitch.setChecked(prefs.getBoolean(Prefs.PERFORMANCE, false));
        bootSwitch.setChecked(prefs.getBoolean(Prefs.BOOT, false));
        ui.show(removePicture, prefs.getInt(Prefs.PICTURE, 0) != 0);
    }

    /** What the clear button clears, named after the kinds that lie, as the desktop menus name it. */
    private int clearLabel(boolean snow, boolean autumn, boolean blossom) {
        if (snow && autumn) return R.string.clear_snow_autumn;
        if (snow && blossom) return R.string.clear_snow_blossom;
        if (autumn && blossom) return R.string.clear_autumn_blossom;
        if (autumn) return R.string.clear_autumn;
        if (blossom) return R.string.clear_blossom;
        return R.string.clear_snow;
    }

    // Over every app

    private void setSnowing(boolean snowing) {
        if (snowing && !Snow.allowed(this)) {
            askOverlay();
            return;
        }
        Prefs.setSnowing(this, snowing);
        // The plain overlay stays up behind a notification, which Android 13 and later ask leave to show.
        if (snowing && !Snow.deep() && Build.VERSION.SDK_INT >= 33 && !askedNotifications && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askedNotifications = true;
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, ASK_NOTIFICATIONS);
        }
        Snow.apply(this);
        refresh();
    }

    /** Opens Android's "Display over other apps" for the app; the snow starts once it is allowed. */
    private void askOverlay() {
        awaitingOverlay = true;
        if (!open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())))) {
            open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private void openDeep() {
        if (Snow.deep()) {
            open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(R.string.deep)
                .setMessage(R.string.deep_explained)
                .setPositiveButton(R.string.open_settings, (dialog, which) -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private boolean open(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException missing) {
            return false;
        }
    }

    private void overlayChanged() {
        if (!started) return;
        bind();
        refresh();
    }

    // The live wallpaper

    private void setWallpaper() {
        Intent direct = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(this, SnowWallpaper.class));
        if (!open(direct) && !open(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))) {
            Toast.makeText(this, R.string.wallpaper_unsupported, Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    private void choosePicture() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        try {
            startActivityForResult(pick, PICK_PICTURE);
        } catch (ActivityNotFoundException missing) {
            Toast.makeText(this, R.string.picture_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void removePicture() {
        File file = new File(getFilesDir(), Prefs.PICTURE_FILE);
        if (file.exists() && !file.delete()) Log.w(TAG, "the picture is still there");
        prefs.edit().putInt(Prefs.PICTURE, 0).apply();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_PICTURE && result == RESULT_OK && data != null && data.getData() != null) keepPicture(data.getData());
    }

    /** Copies the picture picked into the app's own files, upright and no bigger than a screen needs. */
    private void keepPicture(Uri source) {
        new Thread(() -> {
            boolean kept = false;
            try {
                int orientation = ExifInterface.ORIENTATION_NORMAL;
                try (InputStream in = getContentResolver().openInputStream(source)) {
                    if (in != null) orientation = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                } catch (IOException | RuntimeException unknown) {
                    // Upright, then.
                }
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                try (InputStream in = getContentResolver().openInputStream(source)) {
                    BitmapFactory.decodeStream(in, null, options);
                }
                options.inSampleSize = 1;
                while (Math.max(options.outWidth, options.outHeight) / (options.inSampleSize * 2) >= PICTURE_SIDE) options.inSampleSize *= 2;
                options.inJustDecodeBounds = false;
                Bitmap bitmap;
                try (InputStream in = getContentResolver().openInputStream(source)) {
                    bitmap = BitmapFactory.decodeStream(in, null, options);
                }
                if (bitmap != null) {
                    Matrix matrix = new Matrix();
                    float scale = Math.min(1f, (float) PICTURE_SIDE / Math.max(bitmap.getWidth(), bitmap.getHeight()));
                    matrix.postScale(scale, scale);
                    switch (orientation) {
                        case ExifInterface.ORIENTATION_ROTATE_90:
                            matrix.postRotate(90);
                            break;
                        case ExifInterface.ORIENTATION_ROTATE_180:
                            matrix.postRotate(180);
                            break;
                        case ExifInterface.ORIENTATION_ROTATE_270:
                            matrix.postRotate(270);
                            break;
                        case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                            matrix.postScale(-1, 1);
                            break;
                        case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                            matrix.postScale(1, -1);
                            break;
                        default:
                            break;
                    }
                    Bitmap upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                    File draft = new File(getFilesDir(), Prefs.PICTURE_FILE + ".new");
                    try (FileOutputStream out = new FileOutputStream(draft)) {
                        kept = upright.compress(Bitmap.CompressFormat.JPEG, 92, out);
                    }
                    kept = kept && draft.renameTo(new File(getFilesDir(), Prefs.PICTURE_FILE));
                }
            } catch (IOException | RuntimeException | OutOfMemoryError unreadable) {
                Log.w(TAG, "the picture can't be kept", unreadable);
            }
            final boolean done = kept;
            runOnUiThread(() -> {
                if (done) {
                    prefs.edit().putInt(Prefs.PICTURE, Math.max(1, prefs.getInt(Prefs.PICTURE, 0) + 1)).apply();
                } else {
                    Toast.makeText(this, R.string.picture_failed, Toast.LENGTH_LONG).show();
                }
            });
        }, "picture").start();
    }

    // The snow on this screen

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        Snow.watch(watcher);
        prefs.registerOnSharedPreferenceChangeListener(listener);
        // Left on, with what it needs still allowed, the snow comes back with the app.
        if (Prefs.snowing(this) && Snow.allowed(this) && Snow.overlay() == null) Snow.apply(this);
        bind();
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (awaitingOverlay && Snow.allowed(this)) {
            awaitingOverlay = false;
            setSnowing(true);
        }
        refresh();
    }

    @Override
    protected void onStop() {
        started = false;
        Snow.unwatch(watcher);
        prefs.unregisterOnSharedPreferenceChangeListener(listener);
        scroll.removeCallbacks(settled);
        tell(null);
        if (preview != null) preview.setShown(false);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (preview != null) preview.release();
        preview = null;
        super.onDestroy();
    }

    /** Picks the snow this screen talks to: the one over every app while it is up, otherwise its own. */
    private void bind() {
        SnowEngine overlay = Snow.overlay();
        boolean own = overlay == null;
        snowView.setVisibility(own && preview != null ? View.VISIBLE : View.GONE);
        if (preview != null) preview.setShown(own && started);
        tell(own ? preview : overlay);
        reportCards(false);
    }

    /** The snow told until now forgets this screen's cards and its finger: their snow falls, as off a closed window. */
    private void tell(SnowEngine next) {
        if (told == next) return;
        if (told != null) {
            told.setAppWindows(new float[0], 0, false);
            told.pointer(0, 0, false);
        }
        told = next;
    }

    /** Tells the snow where the cards are, as the windows it can lie on; {@code moving} while they scroll. */
    private void reportCards(boolean moving) {
        if (told == null || !started || root.getHeight() == 0) return;
        boolean screen = told != preview;
        int[] at = new int[2];
        int[] origin = new int[2];
        if (!screen) snowView.getLocationInWindow(origin);
        float[] numbers = new float[cards.size() * Native.WINDOW_NUMBERS];
        int count = 0;
        int top = statusBar + ui.dp(10);
        int bottom = root.getHeight();
        for (int i = 0; i < cards.size(); i++) {
            View card = cards.get(i);
            if (card.getWidth() == 0 || card.getVisibility() != View.VISIBLE) continue;
            if (screen) {
                card.getLocationOnScreen(at);
            } else {
                card.getLocationInWindow(at);
            }
            int[] window = new int[2];
            card.getLocationInWindow(window);
            // A card whose top edge is scrolled out of sight keeps its snow for when it is back.
            boolean away = window[1] < top || window[1] > bottom;
            int base = count * Native.WINDOW_NUMBERS;
            numbers[base] = CARD_ID + i;
            numbers[base + 1] = at[0] - origin[0];
            numbers[base + 2] = at[1] - origin[1];
            numbers[base + 3] = at[0] - origin[0] + card.getWidth();
            numbers[base + 4] = at[1] - origin[1] + card.getHeight();
            numbers[base + 5] = ui.dp(Ui.CARD_RADIUS);
            numbers[base + 6] = away ? Native.WINDOW_AWAY : Native.WINDOW_SETTLES;
            count++;
        }
        told.setAppWindows(numbers, count, moving);
    }

    /** The finger sweeps the snow wherever it goes, on its way to whatever it touches. */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (told != null && started) {
            int action = event.getActionMasked();
            boolean down = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_POINTER_DOWN;
            if (told == preview) {
                told.pointer(event.getX(), event.getY(), down);
            } else {
                told.pointer(event.getRawX(), event.getRawY(), down);
            }
        }
        return super.dispatchTouchEvent(event);
    }

    /** Keeps the cards clear of the bars, and tells the snow of a navigation bar with buttons, which it lies on. */
    @SuppressWarnings("deprecation")
    private void inset(WindowInsets insets) {
        int left;
        int top;
        int right;
        int bottom;
        int barLeft;
        int barRight;
        int barBottom;
        if (Build.VERSION.SDK_INT >= 30) {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets tappable = insets.getInsets(WindowInsets.Type.tappableElement());
            left = bars.left;
            top = bars.top;
            right = bars.right;
            bottom = bars.bottom;
            barLeft = tappable.left;
            barRight = tappable.right;
            barBottom = tappable.bottom;
        } else {
            left = insets.getSystemWindowInsetLeft();
            top = insets.getSystemWindowInsetTop();
            right = insets.getSystemWindowInsetRight();
            bottom = insets.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT == 29) {
                Insets tappable = insets.getTappableElementInsets();
                barLeft = tappable.left;
                barRight = tappable.right;
                barBottom = tappable.bottom;
            } else {
                barLeft = left;
                barRight = right;
                barBottom = bottom;
            }
        }
        statusBar = top;
        int side = ui.dp(20);
        scroll.setPadding(left + side, top + ui.dp(28), right + side, bottom + ui.dp(40));
        if (preview != null) preview.setInsets(barLeft, barRight, barBottom);
        reportCards(false);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {}

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (preview != null) preview.surfaceChanged(holder.getSurface(), width, height, getResources().getDisplayMetrics().density);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (preview != null) preview.surfaceDestroyed();
    }

    /** A slider's listener with nothing to say about where the finger starts and stops. */
    private abstract static class Slid implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar bar) {}

        @Override
        public void onStopTrackingTouch(SeekBar bar) {}
    }
}
