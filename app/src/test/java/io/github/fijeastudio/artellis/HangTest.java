package io.github.fijeastudio.artellis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

public class HangTest {

    private static final int W = 1920, H = 1080;

    @Test
    public void threePortraitsFitSideBySide() {
        float[] trio = {0.75f, 1.0f, 0.7f};
        assertTrue(Hang.fits(trio, W, H));
        float h = Hang.height(trio, W, H);
        assertTrue(h >= Hang.MIN_H_GROUP * H);
        assertTrue(h <= Hang.MAX_H_GROUP * H);
    }

    @Test
    public void rowStaysInsideTheArtArea() {
        for (float[] aspects : new float[][]{{0.75f, 1.0f, 0.7f}, {1.33f, 1.25f}, {1.8f}, {0.5f}}) {
            float[][] rects = Hang.layout(aspects, W, H);
            float left = rects[0][0];
            float[] last = rects[rects.length - 1];
            float right = last[0] + last[2];
            assertTrue(left >= Hang.X0 * W - 1);
            assertTrue(right <= Hang.X1 * W + 1);
            for (float[] r : rects) {
                assertTrue(r[1] >= Hang.Y0 * H - 1);
                assertTrue(r[1] + r[3] + Hang.LABEL_SPACE * H <= Hang.Y1 * H + 1);
            }
        }
    }

    @Test
    public void twoLandscapesAreTooSmallAsAThree() {
        assertFalse(Hang.fits(new float[]{1.6f, 1.5f, 1.7f}, W, H));
    }

    @Test
    public void choosesOnlyFittingGroupsWithTheWidestInTheMiddle() {
        Random random = new Random(7);
        float[] pool = {0.75f, 0.8f, 0.7f, 1.0f, 1.5f};
        for (int i = 0; i < 500; i++) {
            int[] chosen = Hang.choose(pool, random, W, H);
            assertTrue(chosen.length >= 1 && chosen.length <= 3);
            float[] a = new float[chosen.length];
            for (int j = 0; j < a.length; j++) a[j] = pool[chosen[j]];
            assertTrue(Hang.fits(a, W, H));
            if (a.length == 3) {
                assertTrue(a[1] >= a[0] && a[1] >= a[2]);
            }
        }
    }

    @Test
    public void aLoneLandscapeIsPreferredForASingle() {
        Random random = new Random(3);
        float[] pool = {1.8f, 1.5f};
        for (int i = 0; i < 50; i++) assertEquals(1, Hang.choose(pool, random, W, H).length);
    }
}
