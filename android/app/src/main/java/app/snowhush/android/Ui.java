package app.snowhush.android;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * How the app's one screen looks: the night of the site and the icon, warm off-white on near-black, condensed
 * capitals for the name, quiet labels, nothing that glows. The controls are Android's own, in those colours. It is
 * all made in code, so the screen needs nothing but the system's fonts and widgets.
 */
final class Ui {
    static final int NIGHT = 0xFF17130F;
    static final int RAISED = 0xFF221D18;
    static final int INK = 0xFFEFE7DA;
    static final int MUTED = 0xFFC7BDB0;
    static final int FAINT = 0xFFA89E91;
    static final int RULE = 0x24EFE7DA;
    static final int LAMP = 0xFFFFD696;
    /** How round a card's corners are, in density-independent pixels: the snow on it follows them. */
    static final float CARD_RADIUS = 14f;

    private final Context context;
    private final float density;

    Ui(Context context) {
        this.context = context;
        density = context.getResources().getDisplayMetrics().density;
    }

    int dp(float value) {
        return Math.round(value * density);
    }

    private TextView text(CharSequence text, float size, int color) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(color);
        view.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        return view;
    }

    /** The app's name: light condensed capitals, as the site sets it. */
    TextView display(CharSequence text) {
        TextView view = text(text, 44, INK);
        view.setTypeface(Typeface.create("sans-serif-condensed-light", Typeface.NORMAL));
        view.setAllCaps(true);
        view.setLetterSpacing(0.03f);
        view.setIncludeFontPadding(false);
        return view;
    }

    /** A small spaced-out label over a group of controls. */
    TextView kicker(CharSequence text) {
        TextView view = text(text, 12, MUTED);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setAllCaps(true);
        view.setLetterSpacing(0.16f);
        return view;
    }

    TextView title(CharSequence text) {
        return text(text, 16, INK);
    }

    TextView summary(CharSequence text) {
        TextView view = text(text, 14, MUTED);
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    TextView value(CharSequence text) {
        TextView view = text(text, 14, INK);
        view.setGravity(Gravity.END);
        view.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
        return view;
    }

    Drawable rounded(int fill, float radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        if (stroke != 0) shape.setStroke(Math.max(1, dp(1)), stroke);
        return shape;
    }

    /** {@code content} with the ripple of a touch over it, clipped to the same corners. */
    Drawable pressable(Drawable content, float radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x30EFE7DA), content, rounded(0xFFFFFFFF, radius, 0));
    }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    LinearLayout row() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    /** A group of settings on a raised panel: one of the screen's windows, as far as the snow is concerned. */
    LinearLayout card() {
        LinearLayout card = column();
        card.setBackground(rounded(RAISED, CARD_RADIUS, 0));
        card.setPadding(dp(20), dp(8), dp(20), dp(8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(22);
        card.setLayoutParams(params);
        return card;
    }

    LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    LinearLayout.LayoutParams spaced(int top) {
        LinearLayout.LayoutParams params = fill();
        params.topMargin = dp(top);
        return params;
    }

    /** A hairline between two rows of a card. */
    View rule() {
        View rule = new View(context);
        rule.setBackgroundColor(RULE);
        rule.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        return rule;
    }

    Switch toggle() {
        Switch toggle = new Switch(context);
        int[][] states = {{android.R.attr.state_checked}, {}};
        toggle.setThumbTintList(new ColorStateList(states, new int[] {INK, FAINT}));
        toggle.setTrackTintList(new ColorStateList(states, new int[] {0x99EFE7DA, 0x40EFE7DA}));
        toggle.setPadding(dp(12), 0, 0, 0);
        return toggle;
    }

    SeekBar slider(int min, int max) {
        SeekBar slider = new SeekBar(context);
        slider.setMin(min);
        slider.setMax(max);
        slider.setProgressTintList(ColorStateList.valueOf(INK));
        slider.setThumbTintList(ColorStateList.valueOf(INK));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(0x66EFE7DA));
        slider.setPadding(dp(8), dp(14), dp(8), dp(14));
        return slider;
    }

    /** One of a few choices: filled while it is the one picked. */
    TextView chip(CharSequence text) {
        TextView chip = text(text, 14, INK);
        chip.setGravity(Gravity.CENTER);
        chip.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        chip.setMinHeight(dp(48));
        chip.setPadding(dp(10), dp(6), dp(10), dp(6));
        chip.setMaxLines(2);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setClickable(true);
        chip.setFocusable(true);
        pick(chip, false);
        return chip;
    }

    void pick(TextView chip, boolean picked) {
        chip.setSelected(picked);
        chip.setTextColor(picked ? NIGHT : INK);
        chip.setBackground(pressable(rounded(picked ? INK : 0x00000000, 8, picked ? 0 : 0x55EFE7DA), 8));
    }

    /** A button as the site's are: square-cornered, in spaced capitals; {@code primary} filled, otherwise outlined. */
    TextView button(CharSequence text, boolean primary) {
        TextView button = text(text, 13, primary ? NIGHT : INK);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setAllCaps(true);
        button.setLetterSpacing(0.12f);
        button.setGravity(Gravity.CENTER);
        button.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        button.setMinHeight(dp(48));
        button.setPadding(dp(18), dp(8), dp(18), dp(8));
        button.setBackground(pressable(rounded(primary ? INK : 0x00000000, 3, primary ? 0 : 0x66EFE7DA), 3));
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    void enable(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.4f);
    }

    void show(View view, boolean shown) {
        view.setVisibility(shown ? View.VISIBLE : View.GONE);
    }
}
