package io.github.fijeastudio.artellis;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screen. Left quarter: a large thin Outfit clock, the weekday and date, and the song while music plays.
 * Right three quarters: a wall of one to three paintings from one place and period, each with its label
 * underneath, aligned to the painting's right edge (title, year / artist). A new wall every five minutes,
 * crossfading. Behind it a soft spotlit wall whose colour follows the time of day; optionally a warm filter
 * on the paintings at night. The clock block and the wall drift a little against burn-in.
 */
final class Scene {

    private static final long INTERVAL_MS = 5 * 60_000L;
    /** The change of walls, from the old one starting to leave to the new one settled. */
    private static final long FADE_MS = 3000L;
    /** The old wall leaves over the first 60%, the new one arrives over the last 60%: a short overlap. */
    private static final long OUT_MS = 1800L;
    private static final long IN_DELAY_MS = FADE_MS - OUT_MS;
    /** How far the walls travel, as a share of the screen height: old sinks, new settles from above. */
    private static final float SHIFT = 0.025f;
    private static final long TICK_MS = 60_000L;
    /** Retry when another refresh in the app (the settings page) is still finishing. */
    private static final long RETRY_MS = 5_000L;
    /** The clock panel: the left quarter of the screen. */
    private static final float PANEL = 0.25f;
    private static final float PANEL_PAD = 0.045f;

    final FrameLayout root;
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final SharedPreferences prefs;
    private final int sw;
    private final int sh;
    private final GradientDrawable wallColour;
    private final FrameLayout stage;
    private final LinearLayout panel;
    private final TextView music;
    private final TextView status;
    private final Feed feed;
    private final NowPlaying nowPlaying;
    private final Typeface regular;
    private final Typeface light;

    private final List<Wall> playlist = new ArrayList<>();
    private int next;
    private View current;
    private final List<ImageView> paintings = new ArrayList<>();
    private final List<View> labels = new ArrayList<>();
    private ExecutorService decoder;
    private ExecutorService network;
    private boolean running;
    private boolean fetching;
    private boolean waitingForFirst;

    private final Runnable advance = this::showNext;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateWallColour();
            updateNightFilter();
            nudgePanel();
            maybeRefresh();
            handler.postDelayed(this, TICK_MS);
        }
    };

    Scene(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("artellis", Context.MODE_PRIVATE);
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = context.getSystemService(WindowManager.class);
        if (wm != null) wm.getDefaultDisplay().getRealMetrics(dm);
        else dm = context.getResources().getDisplayMetrics();
        sw = Math.max(dm.widthPixels, dm.heightPixels);
        sh = Math.min(dm.widthPixels, dm.heightPixels);
        feed = new Feed(context, sw, sh);

        Typeface thin = font(R.font.outfit_extralight);
        regular = font(R.font.outfit_regular);
        light = font(R.font.outfit_light);
        Typeface medium = font(R.font.outfit_medium);

        root = new FrameLayout(context);
        root.setBackgroundColor(0xFF000000);

        // The wall: a soft spotlight centred on the art area, darker towards the edges.
        View wall = new View(context);
        wallColour = new GradientDrawable();
        wallColour.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        wallColour.setGradientCenter((Hang.X0 + Hang.X1) / 2f, 0.45f);
        wallColour.setGradientRadius(sw * 0.75f);
        wall.setBackground(wallColour);
        root.addView(wall, match());
        updateWallColour();

        stage = new FrameLayout(context);
        root.addView(stage, match());

        // ---- The clock panel ----
        int panelW = Math.round(sw * (PANEL - PANEL_PAD - 0.015f));
        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        boolean h24 = DateFormat.is24HourFormat(context);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(true);
        // Sized so the widest time (10:08) still fits the panel; each time then takes its own width, so AM/PM
        // sits right after it. A little end padding keeps the last digit's edge from being shaved.
        final float clockSpacing = -0.02f;
        float ampmPx = sh * 0.022f;
        int ampmGap = Math.round(sh * 0.008f);
        int ampmW = h24 ? 0 : (int) Math.ceil(Math.max(measure(medium, ampmPx, 0.08f, "AM"),
                measure(medium, ampmPx, 0.08f, "PM")) + ampmPx * 0.5f);
        // The clock as wide as the panel allows, never taller than 17% of the screen.
        float clockPx = Math.min(sh * 0.17f,
                100f * (panelW - ampmW - ampmGap) / (widest(thin, 100f, clockSpacing, h24) * 1.05f));
        TextClock clock = new TextClock(context);
        clock.setFormat12Hour("hh:mm");   // always two-digit hours: 09:41
        clock.setFormat24Hour("HH:mm");
        style(clock, thin, clockPx, 0xEBFFFFFF, clockSpacing);
        clock.setMaxLines(1);
        clock.setIncludeFontPadding(false);
        clock.setPadding(0, 0, Math.round(clockPx * 0.04f), 0);
        row.addView(clock, wrapLinear());
        if (!h24) {
            TextClock ampm = new TextClock(context);
            ampm.setFormat12Hour("a");
            ampm.setFormat24Hour("");
            style(ampm, medium, ampmPx, 0x99FFFFFF, 0.08f);
            ampm.setAllCaps(true);
            ampm.setMaxLines(1);
            LinearLayout.LayoutParams lp = wrapLinear();
            lp.setMarginStart(Math.max(0, ampmGap - Math.round(clockPx * 0.04f)));   // the clock's end padding counts
            row.addView(ampm, lp);
        }
        // Wrap content, explicitly: added without params, a vertical LinearLayout makes the row MATCH_PARENT and
        // then squeezes it to the widest other line (the date), which cut the clock to "12:1".
        panel.addView(row, wrapLinear());

        float datePx = sh * 0.021f;
        int indent = Math.round(clockPx * 0.05f);
        TextClock weekday = new TextClock(context);
        weekday.setFormat12Hour("EEEE");
        weekday.setFormat24Hour("EEEE");
        style(weekday, regular, datePx, 0xA6FFFFFF, 0.16f);
        weekday.setAllCaps(true);
        weekday.setMaxLines(1);   // not setSingleLine: that drops the all-caps style
        LinearLayout.LayoutParams weekdayLp = wrapLinear();
        weekdayLp.topMargin = Math.round(sh * 0.012f);
        weekdayLp.setMarginStart(indent);
        panel.addView(weekday, weekdayLp);
        TextClock date = new TextClock(context);
        date.setFormat12Hour("d MMMM");
        date.setFormat24Hour("d MMMM");
        style(date, regular, datePx, 0x8CFFFFFF, 0.16f);
        date.setAllCaps(true);
        date.setMaxLines(1);
        LinearLayout.LayoutParams dateLp = wrapLinear();
        dateLp.topMargin = Math.round(sh * 0.004f);
        dateLp.setMarginStart(indent);
        panel.addView(date, dateLp);

        music = new TextView(context);
        style(music, regular, sh * 0.018f, 0xB3FFFFFF, 0.02f);
        music.setMaxLines(2);
        music.setEllipsize(TextUtils.TruncateAt.END);
        music.setMaxWidth(panelW - indent);
        music.setLineSpacing(0f, 1.12f);
        music.setVisibility(View.GONE);
        LinearLayout.LayoutParams musicLp = wrapLinear();
        musicLp.topMargin = Math.round(sh * 0.035f);
        musicLp.setMarginStart(indent);
        panel.addView(music, musicLp);

        // Wrap content, not a fixed width: a fixed one would squeeze the last thing in the clock row.
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.START);
        panelLp.leftMargin = Math.round(sw * PANEL_PAD);
        root.addView(panel, panelLp);

        // Shown in the art area until the first wall arrives.
        status = new TextView(context);
        style(status, light, sh * 0.02f, 0x99FFFFFF, 0.04f);
        status.setGravity(Gravity.CENTER);
        status.setVisibility(View.GONE);
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
                Math.round((Hang.X1 - Hang.X0) * sw), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP | Gravity.START);
        statusLp.leftMargin = Math.round(Hang.X0 * sw);
        root.addView(status, statusLp);

        nowPlaying = new NowPlaying(context, handler, this::showMusic);
    }

    // ---- Lifecycle -------------------------------------------------------------------------------------------

    void start() {
        if (running) return;
        running = true;
        decoder = Executors.newSingleThreadExecutor();
        network = Executors.newSingleThreadExecutor();
        nowPlaying.start();
        updateWallColour();
        buildPlaylist();
        if (playlist.isEmpty()) {
            waitingForFirst = true;
            showStatus(R.string.gathering);
        } else {
            showNext();
        }
        maybeRefresh();
        handler.postDelayed(tick, TICK_MS);
    }

    void stop() {
        if (!running) return;
        running = false;
        handler.removeCallbacksAndMessages(null);
        feed.cancel();
        if (decoder != null) decoder.shutdownNow();
        if (network != null) network.shutdown();   // the feed sees cancel() and stops after its current request
        decoder = null;
        network = null;
        fetching = false;
        nowPlaying.stop();
        if (current != null) {
            release(current);
            stage.removeView(current);
            current = null;
        }
        paintings.clear();
        labels.clear();
    }

    // ---- Feed ------------------------------------------------------------------------------------------------

    /** Today's walls in random order, then the earlier ones still kept. */
    private void buildPlaylist() {
        playlist.clear();
        String today = Feed.today();
        List<Wall> fresh = new ArrayList<>(), older = new ArrayList<>();
        for (Wall w : feed.saved()) (today.equals(w.day) ? fresh : older).add(w);
        Collections.shuffle(fresh, random);
        Collections.shuffle(older, random);
        playlist.addAll(fresh);
        playlist.addAll(older);
        next = 0;
    }

    private void maybeRefresh() {
        if (!running || fetching || network == null) return;
        if (waitingForFirst) pickUpSaved();
        if (feed.missingToday() == 0) return;
        fetching = true;
        ExecutorService net = network;
        net.execute(() -> {
            boolean ran = true;
            try {
                ran = feed.refresh(wall -> handler.post(() -> arrived(wall)));
            } catch (RuntimeException e) {
                Log.w(Feed.TAG, "refresh failed: " + e);
            }
            boolean busyElsewhere = !ran;
            handler.post(() -> {
                fetching = false;
                if (!running) return;
                if (busyElsewhere) {
                    // The settings page (or the last run) is finishing a download: try again shortly.
                    handler.postDelayed(this::maybeRefresh, RETRY_MS);
                    return;
                }
                if (waitingForFirst) pickUpSaved();
                if (playlist.isEmpty()) showStatus(R.string.offline);
            });
        });
    }

    /** While still waiting for a first wall: hang one another part of the app saved meanwhile. */
    private void pickUpSaved() {
        buildPlaylist();
        if (playlist.isEmpty()) return;
        waitingForFirst = false;
        hideStatus();
        handler.removeCallbacks(advance);
        showNext();
    }

    /** A new wall: hung next, and at once if the screen was still waiting for its first. */
    private void arrived(Wall wall) {
        if (!running) return;
        playlist.add(Math.min(next, playlist.size()), wall);
        if (waitingForFirst) {
            waitingForFirst = false;
            hideStatus();
            handler.removeCallbacks(advance);
            showNext();
        }
    }

    // ---- Walls -----------------------------------------------------------------------------------------------

    private void showNext() {
        if (!running || decoder == null) return;
        if (next >= playlist.size()) {
            buildPlaylist();   // a new day's walls may have arrived meanwhile
            if (playlist.isEmpty()) return;
        }
        Wall wall = playlist.get(next++);
        decoder.execute(() -> {
            List<Bitmap> bitmaps = new ArrayList<>();
            for (Art a : wall.art) {
                Bitmap b = decode(feed.fileOf(a));
                if (b == null) break;
                bitmaps.add(b);
            }
            boolean whole = bitmaps.size() == wall.art.size();
            handler.post(() -> {
                if (!running || !whole) {
                    for (Bitmap b : bitmaps) b.recycle();
                    if (running) handler.postDelayed(advance, 1000);
                    return;
                }
                show(wall, bitmaps);
                handler.postDelayed(advance, INTERVAL_MS);
            });
        });
    }

    private static Bitmap decode(java.io.File file) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeFile(file.getPath(), o);
            if (bmp == null) return null;
            Bitmap gpu = bmp.copy(Bitmap.Config.HARDWARE, false);
            if (gpu == null) return bmp;
            bmp.recycle();
            return gpu;
        } catch (Exception | OutOfMemoryError e) {
            Log.w(Feed.TAG, "decode failed: " + e);
            return null;
        }
    }

    private void show(Wall wall, List<Bitmap> bitmaps) {
        FrameLayout view = new FrameLayout(context);
        float[][] rects = Hang.layout(wall.aspects(), sw, sh);
        List<ImageView> images = new ArrayList<>();
        List<View> wallLabels = new ArrayList<>();
        float prevRight = Hang.X0 * sw;
        for (int i = 0; i < rects.length; i++) {
            float x = rects[i][0], y = rects[i][1], w = rects[i][2], h = rects[i][3];
            Art art = wall.art.get(i);

            ImageView image = new ImageView(context);
            image.setImageBitmap(bitmaps.get(i));
            image.setScaleType(ImageView.ScaleType.FIT_XY);
            // A gentle rounding (about 8 px at 1080p, 16 px at 4K): visibly softer edges, still small enough
            // to leave painted corners, where signatures and dates often are, almost untouched.
            float radius = sh * 0.0074f;
            image.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline outline) {
                    outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                }
            });
            image.setClipToOutline(true);
            image.setElevation(sh * 0.014f);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(w), Math.round(h),
                    Gravity.TOP | Gravity.START);
            lp.leftMargin = Math.round(x);
            lp.topMargin = Math.round(y);
            view.addView(image, lp);
            images.add(image);

            // The label under the painting, flush with its right edge. A narrow painting's label may reach
            // left past it, but never into the neighbour's.
            float right = x + w;
            float leftLimit = i == 0 ? Hang.X0 * sw : prevRight + Hang.GAP * sw * 0.3f;
            float labelW = Math.min(Math.max(w, sw * 0.13f), right - leftLimit);
            View labelView = label(art, Math.round(labelW));
            view.addView(labelView, labelParams(right, labelW, y + h));
            wallLabels.add(labelView);
            prevRight = right;
        }
        view.setTag(R.id.painting, bitmaps);
        view.setAlpha(0f);
        stage.addView(view, match());
        paintings.clear();
        paintings.addAll(images);
        labels.clear();
        labels.addAll(wallLabels);
        updateNightFilter();

        // The old wall fades out while sinking a little; the new one fades in settling from a little above, then
        // drifts a few pixels over the five minutes. Ease in and out, small moves: calm, and light on TV chips.
        View old = current;
        current = view;
        float shift = sh * SHIFT;
        long delay = old != null ? IN_DELAY_MS : 0;
        long in = old != null ? OUT_MS : FADE_MS;
        PathInterpolator ease = new PathInterpolator(0.45f, 0f, 0.55f, 1f);
        float dx = (random.nextFloat() - 0.5f) * sw * 0.008f;
        float dy = (random.nextFloat() - 0.5f) * sh * 0.012f;
        view.setTranslationY(-shift);
        ObjectAnimator arrive = ObjectAnimator.ofPropertyValuesHolder(view,
                PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, -shift, 0f))
                .setDuration(in);
        arrive.setInterpolator(ease);
        ObjectAnimator drift = ObjectAnimator.ofPropertyValuesHolder(view,
                PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, dx),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, dy))
                .setDuration(INTERVAL_MS + FADE_MS);
        drift.setInterpolator(new LinearInterpolator());
        AnimatorSet set = new AnimatorSet();
        set.playSequentially(arrive, drift);
        set.setStartDelay(delay);
        view.setTag(R.id.animation, set);
        set.start();

        if (old != null) {
            Object running = old.getTag(R.id.animation);
            if (running instanceof AnimatorSet) ((AnimatorSet) running).cancel();   // stops its drift where it is
            ObjectAnimator leave = ObjectAnimator.ofPropertyValuesHolder(old,
                    PropertyValuesHolder.ofFloat(View.ALPHA, old.getAlpha(), 0f),
                    PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, old.getTranslationY(),
                            old.getTranslationY() + shift))
                    .setDuration(OUT_MS);
            leave.setInterpolator(ease);
            AnimatorSet out = new AnimatorSet();
            out.play(leave);
            old.setTag(R.id.animation, out);
            out.start();
            handler.postDelayed(() -> {
                release(old);
                stage.removeView(old);
            }, OUT_MS + 200);
        }
    }

    private View label(Art art, int width) {
        LinearLayout label = new LinearLayout(context);
        label.setOrientation(LinearLayout.VERTICAL);
        label.setGravity(Gravity.END);
        // A few pixels past the painting's edge for the last letter and its shadow; the text itself still
        // ends flush with the painting.
        label.setPadding(0, 0, labelPad(), 0);

        String line = art.titleLine();
        SpannableString text = new SpannableString(line);
        if (!art.date.isEmpty() && line.length() > art.title.length()) {
            // The year a shade quieter than the title.
            text.setSpan(new ForegroundColorSpan(0x99FFFFFF), art.title.length(), line.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextView title = new TextView(context);
        style(title, regular, sh * 0.0155f, 0xD9FFFFFF, 0.01f);
        title.setGravity(Gravity.END);
        title.setLineSpacing(0f, 1.12f);
        title.setMaxWidth(width);
        title.setText(text);
        label.addView(title, wrapLinear());

        TextView artist = new TextView(context);
        style(artist, light, sh * 0.0135f, 0x99FFFFFF, 0.02f);
        artist.setGravity(Gravity.END);
        artist.setMaxWidth(width);
        artist.setText(art.artist);
        LinearLayout.LayoutParams lp = wrapLinear();
        lp.topMargin = Math.round(sh * 0.004f);
        label.addView(artist, lp);
        return label;
    }

    private int labelPad() {
        return Math.round(sh * 0.006f);
    }

    private FrameLayout.LayoutParams labelParams(float right, float width, float bottom) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(width) + labelPad(),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lp.leftMargin = Math.round(right - width);
        lp.topMargin = Math.round(bottom + sh * 0.02f);
        return lp;
    }

    @SuppressWarnings("unchecked")
    private static void release(View view) {
        Object set = view.getTag(R.id.animation);
        if (set instanceof AnimatorSet) ((AnimatorSet) set).cancel();
        Object bitmaps = view.getTag(R.id.painting);
        view.setTag(R.id.painting, null);
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                View child = g.getChildAt(i);
                if (child instanceof ImageView) ((ImageView) child).setImageDrawable(null);
            }
        }
        if (bitmaps instanceof List) for (Bitmap b : (List<Bitmap>) bitmaps) b.recycle();
    }

    // ---- Wall colour and night filter --------------------------------------------------------------------------

    private static float hourNow() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60f;
    }

    private void updateWallColour() {
        int[] colours = Palette.at(hourNow());
        wallColour.setColors(new int[]{colours[0], colours[1]});
    }

    /** Warm light at night (optional, screensaver settings): less blue on the paintings in the chosen hours. */
    private void updateNightFilter() {
        float f = prefs.getBoolean(SettingsActivity.NIGHT_FILTER, SettingsActivity.NIGHT_FILTER_DEFAULT)
                ? Palette.night(hourNow(), prefs.getInt(SettingsActivity.NIGHT_START, Palette.DEFAULT_START),
                        prefs.getInt(SettingsActivity.NIGHT_END, Palette.DEFAULT_END))
                : 0f;
        for (ImageView image : paintings) {
            if (f <= 0f) image.clearColorFilter();
            else image.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(Palette.warm(f))));
        }
        // The text dims with the paintings; the wall is already near-black at night.
        float textAlpha = Palette.dim(f);
        panel.setAlpha(textAlpha);
        for (View label : labels) label.setAlpha(textAlpha);
    }

    // ---- Text ------------------------------------------------------------------------------------------------

    private void showMusic(String line) {
        if (line == null) {
            music.animate().alpha(0f).setDuration(600).withEndAction(() -> music.setVisibility(View.GONE)).start();
            return;
        }
        music.setText("♪  " + line);
        if (music.getVisibility() != View.VISIBLE) {
            music.setAlpha(0f);
            music.setVisibility(View.VISIBLE);
        }
        music.animate().alpha(1f).setDuration(600).start();
    }

    /** Burn-in guard: the clock block wanders a little each minute. */
    private void nudgePanel() {
        float x = random.nextFloat() * sw * 0.008f;
        float y = (random.nextFloat() - 0.5f) * sh * 0.06f;
        panel.animate().translationX(x).translationY(y).setDuration(2000).start();
    }

    private void showStatus(int text) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        status.setVisibility(View.GONE);
    }

    private Typeface font(int id) {
        try {
            Typeface t = context.getResources().getFont(id);
            return t != null ? t : Typeface.DEFAULT;
        } catch (RuntimeException e) {
            return Typeface.DEFAULT;
        }
    }

    /** Width of the widest time the clock can show (Outfit's digits are proportional). */
    private static float widest(Typeface face, float px, float spacing, boolean h24) {
        String[] samples = h24 ? new String[]{"00:00", "08:08", "20:00", "23:58", "18:48"}
                : new String[]{"10:00", "10:08", "12:00", "12:58", "11:48", "08:08"};
        float max = 1f;
        for (String s : samples) max = Math.max(max, measure(face, px, spacing, s));
        return max;
    }

    private static float measure(Typeface face, float px, float spacing, String text) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(face);
        p.setTextSize(px);
        p.setLetterSpacing(spacing);
        return Math.max(1f, p.measureText(text));
    }

    private static void style(TextView v, Typeface face, float px, int color, float spacing) {
        v.setTypeface(face);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        v.setTextColor(color);
        v.setLetterSpacing(spacing);
        v.setShadowLayer(px * 0.25f, 0f, 0f, 0x66000000);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static LinearLayout.LayoutParams wrapLinear() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
