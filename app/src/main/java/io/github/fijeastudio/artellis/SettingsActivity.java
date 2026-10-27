package io.github.fijeastudio.artellis;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Artellis's about-and-settings page, opened from the TV's screensaver menu (Settings > Screen saver > Artellis):
 * what it is, where the art comes from, how much is saved, and the one setting: warm light at night, with its
 * start and end time in 30-minute steps. In Artellis's own look: the wall colour of the hour and Outfit.
 *
 * Remote: OK switches warm light on and off. On a time, OK starts editing it (arrows appear); up/down or
 * left/right change it by 30 minutes; OK or Back finish.
 *
 * Over ADB, without opening the page:
 *   am start -n io.github.fijeastudio.artellis/.SettingsActivity --ez night_filter true|false
 *   am start -n io.github.fijeastudio.artellis/.SettingsActivity --ez settings_tile true|false
 * The second shows or hides an "Artellis settings" tile in the app list, for TVs without a screensaver menu
 * (Google TV). It is hidden by default.
 */
public class SettingsActivity extends Activity {

    static final String NIGHT_FILTER = "night_filter";
    /** Warm light at night is on until someone switches it off. */
    static final boolean NIGHT_FILTER_DEFAULT = true;
    static final String NIGHT_START = "night_start";
    static final String NIGHT_END = "night_end";
    static final String SETTINGS_TILE = "settings_tile";
    private static final String TILE = "io.github.fijeastudio.artellis.SettingsTile";

    private SharedPreferences prefs;
    private TextView toggleState;
    private View timeRow;
    private TextView startChip;
    private TextView endChip;
    private TextView editing;
    private TextView previewCaption;
    private ImageView preview;
    /** Under the preview while there is no painting yet: on its way, or waiting for the network. */
    private TextView previewNote;
    private int previewMaxH;
    private int previewMaxW;
    private TextView savedLine;
    /** Fetching the first wall while the page is open and nothing is saved yet; null otherwise. */
    private Feed fetcher;
    /** Between onStart and onStop: no fetching once the page is out of sight. */
    private boolean visible;
    private ValueAnimator previewFade;
    /** The preview's current warm-light strength; -1 before the first painting is shown. */
    private float previewLevel = -1f;
    private Typeface regular;
    private Typeface light;
    private float dp;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("artellis", MODE_PRIVATE);
        if (getIntent() != null && (getIntent().hasExtra(NIGHT_FILTER) || getIntent().hasExtra(SETTINGS_TILE))) {
            if (getIntent().hasExtra(NIGHT_FILTER)) {
                prefs.edit().putBoolean(NIGHT_FILTER, getIntent().getBooleanExtra(NIGHT_FILTER, false)).commit();
            }
            if (getIntent().hasExtra(SETTINGS_TILE)) {
                boolean show = getIntent().getBooleanExtra(SETTINGS_TILE, false);
                getPackageManager().setComponentEnabledSetting(new ComponentName(getPackageName(), TILE),
                        show ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
            }
            finish();
            return;
        }
        dp = getResources().getDisplayMetrics().density;
        regular = font(R.font.outfit_regular);
        light = font(R.font.outfit_light);
        Typeface thin = font(R.font.outfit_extralight);

        FrameLayout root = new FrameLayout(this);
        Calendar now = Calendar.getInstance();
        int[] wall = Palette.at(now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60f);
        GradientDrawable background = new GradientDrawable();
        background.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        background.setGradientCenter(0.35f, 0.45f);
        background.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.8f);
        background.setColors(new int[]{wall[0], wall[1]});
        root.setBackground(background);

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int side = px(96);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams pageLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL);
        pageLp.leftMargin = side;
        pageLp.rightMargin = side;
        pageLp.bottomMargin = px(24);   // keeps clear of the GitHub line
        root.addView(page, pageLp);

        // Top, full width: what it is.
        page.addView(text(getString(R.string.app_name), thin, 42, 0xEBFFFFFF, 0));
        page.addView(text(getString(R.string.tagline), regular, 20, 0xD9FFFFFF, px(2)));

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams columnsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        columnsLp.topMargin = px(32);
        page.addView(columns, columnsLp);

        // Left column: where the art comes from, what is saved, the promises.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f);
        leftLp.rightMargin = px(56);
        columns.addView(left, leftLp);
        left.addView(text(getString(R.string.about), light, 15, 0x99FFFFFF, px(8)));
        savedLine = text("", light, 15, 0x99FFFFFF, px(24));
        updateSaved();
        left.addView(savedLine);
        left.addView(text(getString(R.string.storage_privacy), light, 15, 0x99FFFFFF, px(4)));

        // Right column: the one setting, its times, and the preview.
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        columns.addView(column, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f));

        LinearLayout toggle = new LinearLayout(this);
        toggle.setOrientation(LinearLayout.HORIZONTAL);
        toggle.setGravity(Gravity.CENTER_VERTICAL);
        focusable(toggle, px(12), px(8));
        TextView label = text(getString(R.string.night_filter), regular, 18, 0xEBFFFFFF, 0);
        toggle.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        toggleState = text("", regular, 18, 0xB3FFFFFF, 0);
        toggle.addView(toggleState);
        toggle.setOnClickListener(v -> {
            prefs.edit().putBoolean(NIGHT_FILTER, !prefs.getBoolean(NIGHT_FILTER, NIGHT_FILTER_DEFAULT)).apply();
            refresh();
        });
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        column.addView(toggle, toggleLp);
        // The focusable rows have 12dp of padding for their glow; plain lines are indented to match.
        TextView hint = text(getString(R.string.night_filter_hint), light, 14, 0x80FFFFFF, 0);
        indent(hint);
        column.addView(hint);

        // Start and end on one line.
        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.HORIZONTAL);
        times.setGravity(Gravity.CENTER_VERTICAL);
        startChip = chip(NIGHT_START, Palette.DEFAULT_START);
        endChip = chip(NIGHT_END, Palette.DEFAULT_END);
        times.addView(startChip);
        TextView to = text(getString(R.string.to), light, 16, 0x99FFFFFF, 0);
        LinearLayout.LayoutParams toLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toLp.leftMargin = px(4);
        toLp.rightMargin = px(4);
        times.addView(to, toLp);
        times.addView(endChip);
        LinearLayout.LayoutParams timesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timesLp.topMargin = px(6);
        column.addView(times, timesLp);
        timeRow = times;

        // The live preview: a recent painting, warm light at full strength when on. Until one is saved, a stand-in
        // canvas shows the effect while the first wall downloads (see onStart).
        previewCaption = text(getString(R.string.preview), regular, 12, 0x80FFFFFF, px(22));
        previewCaption.setLetterSpacing(0.16f);
        previewCaption.setAllCaps(true);
        indent(previewCaption);
        column.addView(previewCaption);
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_XY);
        float radius = screenH * 0.0074f;   // the screensaver's corners
        preview.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        preview.setClipToOutline(true);
        column.addView(preview);
        previewNote = text("", light, 13, 0x80FFFFFF, px(8));
        previewNote.setVisibility(View.GONE);
        indent(previewNote);
        column.addView(previewNote);
        previewMaxH = Math.round(screenH * 0.27f);
        previewMaxW = Math.round((screenW - 2 * side) * 0.58f * 0.9f);
        if (!loadPreview()) showPlaceholder();

        // Bottom left: where the project lives.
        TextView github = text(getString(R.string.github), light, 13, 0x73FFFFFF, 0);
        FrameLayout.LayoutParams githubLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        githubLp.leftMargin = side;
        githubLp.bottomMargin = px(36);
        root.addView(github, githubLp);

        setContentView(root);
        refresh();
        toggle.requestFocus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        // A fresh install has nothing saved: fetch one wall now, so the preview needn't wait for the screensaver.
        if (preview != null && new Feed(this, 0, 0).saved().isEmpty()) fetchFirst();
    }

    @Override
    protected void onStop() {
        visible = false;
        // Stops after the current request, so the screensaver (often started next) can take over.
        if (fetcher != null) fetcher.cancel();
        fetcher = null;
        super.onStop();
    }

    /** One wall, on a background thread, sized for this screen like the screensaver's. */
    private void fetchFirst() {
        if (!visible || fetcher != null || isFinishing()) return;
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        Feed feed = new Feed(this, Math.max(dm.widthPixels, dm.heightPixels), Math.min(dm.widthPixels, dm.heightPixels));
        fetcher = feed;
        showNote(R.string.preview_waiting);
        new Thread(() -> {
            boolean ran = true;
            try {
                ran = feed.refresh(wall -> runOnUiThread(() -> {
                    if (fetcher != feed) return;
                    updateSaved();
                    loadPreview();
                }), 1);
            } catch (RuntimeException e) {
                Log.w(Feed.TAG, "first wall failed: " + e);
            }
            boolean busyElsewhere = !ran;
            runOnUiThread(() -> {
                if (fetcher != feed) return;   // the page was left meanwhile
                fetcher = null;
                if (!feed.saved().isEmpty()) return;
                if (busyElsewhere) {
                    // The screensaver's last run is still finishing a download: try again shortly.
                    preview.postDelayed(this::fetchFirst, 5_000L);
                } else {
                    showNote(R.string.preview_offline);
                }
            });
        }, "artellis-first-wall").start();
    }

    private void updateSaved() {
        long[] stats = new Feed(this, 0, 0).stats();
        int walls = (int) stats[0];
        savedLine.setText(getResources().getQuantityString(R.plurals.walls_saved, walls, walls)
                + "  ·  " + megabytes(stats[1]));
    }

    /**
     * Until a painting is saved: a stand-in canvas, sky to field, so warm light still shows (blue is what it
     * takes away). The size of a landscape painting in the preview.
     */
    private void showPlaceholder() {
        GradientDrawable canvas = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF9FB4C7, 0xFFD9CDAF, 0xFF7B855F});
        int h = previewMaxH;
        int w = Math.min(previewMaxW, Math.round(h * 4f / 3f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.topMargin = px(8);
        lp.leftMargin = px(12);
        preview.setLayoutParams(lp);
        preview.setImageDrawable(canvas);
        previewLevel = -1f;
        fadePreview(prefs.getBoolean(NIGHT_FILTER, NIGHT_FILTER_DEFAULT) ? 1f : 0f);
    }

    private void showNote(int text) {
        previewNote.setText(text);
        previewNote.setVisibility(View.VISIBLE);
    }

    /** A start or end time: OK to edit, then up/down or left/right in 30-minute steps, OK or Back to finish. */
    private TextView chip(String key, int fallback) {
        TextView chip = text("", regular, 18, 0xEBFFFFFF, 0);
        focusable(chip, px(12), px(8));
        chip.setTag(key);
        chip.setOnKeyListener((v, code, event) -> {
            // Key-up: swallowed while editing, so the arrows don't also move the focus.
            if (event.getAction() != KeyEvent.ACTION_DOWN) return editing == v;
            boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
            if (editing != v) {
                if (!ok) return false;   // normal focus moves
                editing = chip;
                show(chip);
                return true;
            }
            if (ok || code == KeyEvent.KEYCODE_BACK) {
                editing = null;
                show(chip);
                return true;
            }
            int steps = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1
                    : code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 0;
            if (steps != 0) {
                String other = NIGHT_START.equals(key) ? NIGHT_END : NIGHT_START;
                int otherValue = prefs.getInt(other, NIGHT_START.equals(other) ? Palette.DEFAULT_START : Palette.DEFAULT_END);
                int value = Palette.step(prefs.getInt(key, fallback), steps);
                if (value == otherValue) value = Palette.step(value, steps);   // start and end never meet
                prefs.edit().putInt(key, value).apply();
                show(chip);
            }
            return true;   // while editing, the arrows belong to the time
        });
        return chip;
    }

    private void refresh() {
        boolean on = prefs.getBoolean(NIGHT_FILTER, NIGHT_FILTER_DEFAULT);
        toggleState.setText(on ? R.string.on : R.string.off);
        fadePreview(on ? 1f : 0f);
        // Off: the times are dimmed and the remote skips them.
        timeRow.setAlpha(on ? 1f : 0.35f);
        for (TextView chip : new TextView[]{startChip, endChip}) {
            chip.setFocusable(on);
            if (!on && editing == chip) editing = null;
            show(chip);
        }
    }

    private void show(TextView chip) {
        String key = (String) chip.getTag();
        int minutes = prefs.getInt(key, NIGHT_START.equals(key) ? Palette.DEFAULT_START : Palette.DEFAULT_END);
        String time = time(minutes);
        boolean edit = editing == chip;
        chip.setText(edit ? "‹  " + time + "  ›" : time);
        GradientDrawable bg = (GradientDrawable) chip.getBackground();
        if (edit) {
            bg.setColor(0x40FFFFFF);
            bg.setStroke(Math.max(1, px(1.5f)), 0x99FFFFFF);
        } else {
            bg.setColor(chip.hasFocus() ? 0x26FFFFFF : 0x00000000);
            bg.setStroke(0, 0x00000000);
        }
    }

    /**
     * The brightest of the most recent paintings (warm light hardly shows on a dark one), decoded off the main
     * thread at preview size. False when no painting is saved yet.
     */
    private boolean loadPreview() {
        int maxH = previewMaxH;
        int maxW = previewMaxW;
        Feed feed = new Feed(this, 0, 0);
        java.util.List<Art> recent = new java.util.ArrayList<>();
        for (Wall w : feed.saved()) {
            for (Art a : w.art) if (recent.size() < 8) recent.add(a);
            if (recent.size() >= 8) break;
        }
        if (recent.isEmpty()) return false;
        new Thread(() -> {
            Art art = recent.get(0);
            float best = -1f;
            for (Art a : recent) {
                float b = brightness(feed.fileOf(a));
                if (b > best) { best = b; art = a; }
            }
            java.io.File file = feed.fileOf(art);
            Bitmap bitmap = null;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = Math.max(1, art.height / Math.max(1, maxH));   // art is the chosen one
                bitmap = BitmapFactory.decodeFile(file.getPath(), o);
            } catch (RuntimeException | OutOfMemoryError ignored) {
            }
            Bitmap shown = bitmap;
            runOnUiThread(() -> {
                if (shown == null || isFinishing()) return;
                float scale = Math.min((float) maxH / shown.getHeight(), (float) maxW / shown.getWidth());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        Math.round(shown.getWidth() * scale), Math.round(shown.getHeight() * scale));
                lp.topMargin = px(8);
                lp.leftMargin = px(12);
                preview.setLayoutParams(lp);
                preview.setImageBitmap(shown);
                previewNote.setVisibility(View.GONE);
                previewLevel = -1f;
                fadePreview(prefs.getBoolean(NIGHT_FILTER, NIGHT_FILTER_DEFAULT) ? 1f : 0f);
            });
        }, "artellis-preview").start();
        return true;
    }

    /** Average brightness of a picture (0 to 255), from a tiny decode. */
    private static float brightness(java.io.File file) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 32;
            Bitmap b = BitmapFactory.decodeFile(file.getPath(), o);
            if (b == null) return 0f;
            long sum = 0;
            int n = 0;
            for (int y = 0; y < b.getHeight(); y++) {
                for (int x = 0; x < b.getWidth(); x++) {
                    int c = b.getPixel(x, y);
                    sum += (((c >> 16) & 0xFF) * 3 + ((c >> 8) & 0xFF) * 6 + (c & 0xFF)) / 10;
                    n++;
                }
            }
            b.recycle();
            return n == 0 ? 0f : (float) sum / n;
        } catch (RuntimeException | OutOfMemoryError e) {
            return 0f;
        }
    }

    /** Warm light on the preview at full strength (1) or none (0), fading over half a second. */
    private void fadePreview(float target) {
        if (preview == null || preview.getVisibility() != View.VISIBLE) return;
        if (previewFade != null) previewFade.cancel();
        float from = previewLevel < 0f ? target : previewLevel;
        previewFade = ValueAnimator.ofFloat(from, target);
        previewFade.setDuration(previewLevel < 0f ? 0 : 500);
        previewFade.addUpdateListener(a -> {
            previewLevel = (float) a.getAnimatedValue();
            if (previewLevel <= 0f) preview.clearColorFilter();
            else preview.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(Palette.warm(previewLevel))));
        });
        previewFade.start();
    }

    /** "9:00 PM", or "21:00" when the TV uses the 24-hour clock. */
    private String time(int minutes) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, minutes / 60);
        c.set(Calendar.MINUTE, minutes % 60);
        String pattern = DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm a";
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(c.getTime());
    }

    private void indent(TextView v) {
        ((LinearLayout.LayoutParams) v.getLayoutParams()).leftMargin = px(12);
    }

    /** Focusable with a soft rounded glow when focused. */
    private void focusable(View v, int padX, int padY) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setPadding(padX, padY, padX, padY);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(12));
        bg.setColor(0x00000000);
        v.setBackground(bg);
        v.setOnFocusChangeListener((view, has) -> {
            if (view instanceof TextView && view.getTag() != null) {
                if (!has && editing == view) editing = null;
                show((TextView) view);
            } else {
                bg.setColor(has ? 0x26FFFFFF : 0x00000000);
            }
        });
    }

    private static String megabytes(long bytes) {
        float mb = bytes / (1024f * 1024f);
        return String.format(Locale.getDefault(), mb < 10f ? "%.1f MB" : "%.0f MB", mb);
    }

    /** A text line in the column, topMargin in pixels. */
    private TextView text(String s, Typeface face, float sp, int color, int topMargin) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTypeface(face);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setLineSpacing(0f, 1.15f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        v.setLayoutParams(lp);
        return v;
    }

    private int px(float value) {
        return Math.round(value * dp);
    }

    private Typeface font(int id) {
        try {
            Typeface t = getResources().getFont(id);
            return t != null ? t : Typeface.DEFAULT;
        } catch (RuntimeException e) {
            return Typeface.DEFAULT;
        }
    }
}
