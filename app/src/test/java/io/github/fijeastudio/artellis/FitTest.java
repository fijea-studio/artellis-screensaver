package io.github.fijeastudio.artellis;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FitTest {

    @Test
    public void savesAtThePlaceItHangs() {
        assertArrayEquals(new int[]{536, 700}, Fit.target(1686, 2200, 700));
    }

    @Test
    public void neverScalesUp() {
        assertArrayEquals(new int[]{800, 600}, Fit.target(800, 600, 700));
    }

    @Test
    public void sampleSizeKeepsEnoughPixels() {
        assertEquals(4, Fit.sampleSize(4000, 5200, 700));
        assertEquals(1, Fit.sampleSize(800, 600, 700));
    }
}
