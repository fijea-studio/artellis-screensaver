package io.github.fijeastudio.artellis;

/**
 * The wall's colour through the day: warm stone in the morning, neutral taupe at midday, amber-brown late in
 * the afternoon, deep taupe in the evening, near-black with an amber tint at night. Each a pair (centre of the
 * soft spotlight, edges), blended between the hours below. All dark enough for white text.
 */
final class Palette {

    private static final float[] HOURS = {0f, 5f, 6.5f, 9f, 15f, 17f, 19f, 21f, 24f};
    private static final int[] CENTRE = {
            0xFF1C1612, 0xFF1C1612, 0xFF3B352F, 0xFF56514B, 0xFF56514B, 0xFF4E3E31, 0xFF322923, 0xFF1C1612, 0xFF1C1612};
    private static final int[] EDGE = {
            0xFF080605, 0xFF080605, 0xFF14110F, 0xFF201E1B, 0xFF201E1B, 0xFF1B140F, 0xFF0F0C0A, 0xFF080605, 0xFF080605};

    private Palette() {}

    /** {centre, edge} at this hour of the day (0 to 24, fractional). */
    static int[] at(float hour) {
        hour = ((hour % 24f) + 24f) % 24f;
        int i = 0;
        while (i < HOURS.length - 2 && hour >= HOURS[i + 1]) i++;
        float f = (hour - HOURS[i]) / (HOURS[i + 1] - HOURS[i]);
        return new int[]{mix(CENTRE[i], CENTRE[i + 1], f), mix(EDGE[i], EDGE[i + 1], f)};
    }

    /** Default warm-light window: 21:00 to 07:00, in minutes of the day. */
    static final int DEFAULT_START = 21 * 60;
    static final int DEFAULT_END = 7 * 60;
    /** The fade before the start and after the end, in minutes. */
    static final int FADE = 30;

    /** Warm-light strength with the default window. */
    static float night(float hour) {
        return night(hour, DEFAULT_START, DEFAULT_END);
    }

    /**
     * Warm-light strength at this hour (0 to 24, fractional) for a window from start to end (minutes of the
     * day; it may cross midnight): 1 inside it, fading over FADE minutes before the start and after the end.
     */
    static float night(float hour, int start, int end) {
        float m = ((hour * 60f) % 1440f + 1440f) % 1440f;
        boolean inside = start <= end ? m >= start && m <= end : m >= start || m <= end;
        if (inside) return 1f;
        float before = ((start - m) % 1440f + 1440f) % 1440f;
        float after = ((m - end) % 1440f + 1440f) % 1440f;
        return Math.max(0f, 1f - Math.min(before, after) / FADE);
    }

    /** A time in minutes of the day moved by steps of 30 minutes, wrapping round midnight. */
    static int step(int minutes, int steps) {
        return ((minutes + steps * 30) % 1440 + 1440) % 1440;
    }

    /** How much dimmer everything gets at full warm light. */
    static final float DIM = 0.15f;

    /** Brightness multiplier at warm-light strength f, for the text: 1 by day, 0.85 at full strength. */
    static float dim(float f) {
        return 1f - DIM * f;
    }

    /**
     * Warm light's colour matrix at strength f: blue cut by up to 50%, green by 18%, red barely, and all of it
     * 15% dimmer, so a dark room isn't lit by the screen. 4x5 row-major, for android.graphics.ColorMatrix.
     */
    static float[] warm(float f) {
        float d = dim(f);
        float r = (1f - 0.04f * f) * d, g = (1f - 0.18f * f) * d, b = (1f - 0.50f * f) * d;
        return new float[]{
                r, 0, 0, 0, 0,
                0, g, 0, 0, 0,
                0, 0, b, 0, 0,
                0, 0, 0, 1, 0};
    }

    static int mix(int a, int b, float f) {
        f = Math.max(0f, Math.min(1f, f));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000
                | Math.round(ar + (br - ar) * f) << 16
                | Math.round(ag + (bg - ag) * f) << 8
                | Math.round(ab + (bb - ab) * f);
    }
}
