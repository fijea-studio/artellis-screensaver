package io.github.fijeastudio.artellis;

/** Small picture arithmetic, unit-tested. */
final class Fit {

    private Fit() {}

    /** Biggest power-of-two sample size that keeps the decoded picture at least targetH tall. */
    static int sampleSize(int w, int h, int targetH) {
        int sample = 1;
        while (h / (sample * 2) >= targetH && w / (sample * 2) >= 1) sample *= 2;
        return sample;
    }

    /** {width, height} to save a w x h picture at for a place targetH tall: never larger than the picture. */
    static int[] target(int w, int h, int targetH) {
        if (w <= 0 || h <= 0) return new int[]{0, 0};
        int th = Math.min(h, Math.max(1, targetH));
        int tw = Math.max(1, Math.round((float) w * th / h));
        return new int[]{tw, th};
    }
}
