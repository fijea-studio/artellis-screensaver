package io.github.fijeastudio.artellis;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * How a wall is hung: one to three paintings in a row at the same height in the right three quarters of the
 * screen, each with its label underneath. Plain arithmetic in screen fractions, unit-tested.
 */
final class Hang {

    /** The art area: right of the clock panel (a quarter of the screen), with margins. */
    static final float X0 = 0.275f, X1 = 0.965f, Y0 = 0.07f, Y1 = 0.93f;
    /** Room kept under the paintings for the labels, as a share of the screen height. */
    static final float LABEL_SPACE = 0.14f;
    /** Gap between paintings, as a share of the screen width. */
    static final float GAP = 0.035f;
    static final float MAX_H_GROUP = 0.62f;
    static final float MAX_H_SOLO = 0.66f;
    /** A group is hung only if every painting is at least this tall (share of the screen height). */
    static final float MIN_H_GROUP = 0.40f;
    /** At or above this width-to-height ratio a painting counts as a landscape for a wall of its own. */
    static final float WIDE = 1.3f;

    private Hang() {}

    /** Common painting height in pixels for a row with these width-to-height ratios. */
    static float height(float[] aspects, int sw, int sh) {
        float sum = 0f;
        for (float a : aspects) sum += a;
        if (sum <= 0f) return 0f;
        int n = aspects.length;
        float width = (X1 - X0) * sw - (n - 1) * GAP * sw;
        float maxH = Math.min((n == 1 ? MAX_H_SOLO : MAX_H_GROUP) * sh, (Y1 - Y0 - LABEL_SPACE) * sh);
        return Math.min(maxH, width / sum);
    }

    static boolean fits(float[] aspects, int sw, int sh) {
        return aspects.length == 1 || height(aspects, sw, sh) >= MIN_H_GROUP * sh;
    }

    /** Each painting's {left, top, width, height} in pixels: the row centred in the art area. */
    static float[][] layout(float[] aspects, int sw, int sh) {
        float h = height(aspects, sw, sh);
        int n = aspects.length;
        float total = (n - 1) * GAP * sw;
        for (float a : aspects) total += a * h;
        float x = X0 * sw + ((X1 - X0) * sw - total) / 2f;
        float block = h + LABEL_SPACE * sh;
        float top = Y0 * sh + ((Y1 - Y0) * sh - block) / 2f;
        float[][] out = new float[n][];
        for (int i = 0; i < n; i++) {
            float w = aspects[i] * h;
            out[i] = new float[]{x, top, w, h};
            x += w + GAP * sw;
        }
        return out;
    }

    /**
     * Picks the paintings for one wall from the candidates' ratios: three (45%), two (35%) or one (20%),
     * falling back to fewer when a group would hang too small. A single painting is a landscape when there is
     * one, else a portrait shown large. Returns indices into aspects, in hanging order.
     */
    static int[] choose(float[] aspects, Random random, int sw, int sh) {
        int n = Math.min(aspects.length, 10);
        if (n == 0) return new int[0];
        float roll = random.nextFloat();
        int want = roll < 0.45f ? 3 : roll < 0.80f ? 2 : 1;
        for (int k = Math.min(want, n); k >= 2; k--) {
            int[] group = bestGroup(aspects, n, k, random, sw, sh);
            if (group != null) return group;
        }
        for (int i = 0; i < n; i++) if (aspects[i] >= WIDE) return new int[]{i};
        return new int[]{0};
    }

    /** A random pick among the three best-fitting groups of k, so walls vary; null when none fits. */
    private static int[] bestGroup(float[] aspects, int n, int k, Random random, int sw, int sh) {
        List<int[]> fitting = new ArrayList<>();
        List<Float> heights = new ArrayList<>();
        int[] idx = new int[k];
        collect(aspects, n, k, 0, 0, idx, fitting, heights, sw, sh);
        if (fitting.isEmpty()) return null;
        // Sort by height, tallest first (a simple selection: the lists are small).
        for (int i = 0; i < fitting.size(); i++) {
            int best = i;
            for (int j = i + 1; j < fitting.size(); j++) if (heights.get(j) > heights.get(best)) best = j;
            int[] g = fitting.get(i); fitting.set(i, fitting.get(best)); fitting.set(best, g);
            Float h = heights.get(i); heights.set(i, heights.get(best)); heights.set(best, h);
        }
        int top = Math.min(3, fitting.size());
        int[] pick = fitting.get(random.nextInt(top));
        // Hang the widest in the middle of a three: it reads as a centrepiece.
        if (k == 3) {
            int mid = 0;
            for (int i = 1; i < 3; i++) if (aspects[pick[i]] > aspects[pick[mid]]) mid = i;
            int[] order = new int[3];
            int[] others = new int[2];
            int o = 0;
            for (int i = 0; i < 3; i++) if (i != mid) others[o++] = pick[i];
            order[0] = others[0]; order[1] = pick[mid]; order[2] = others[1];
            return order;
        }
        return pick.clone();
    }

    private static void collect(float[] aspects, int n, int k, int start, int depth, int[] idx,
                                List<int[]> out, List<Float> heights, int sw, int sh) {
        if (depth == k) {
            float[] a = new float[k];
            for (int i = 0; i < k; i++) a[i] = aspects[idx[i]];
            if (fits(a, sw, sh)) {
                out.add(idx.clone());
                heights.add(height(a, sw, sh));
            }
            return;
        }
        for (int i = start; i < n; i++) {
            idx[depth] = i;
            collect(aspects, n, k, i + 1, depth + 1, idx, out, heights, sw, sh);
        }
    }
}
