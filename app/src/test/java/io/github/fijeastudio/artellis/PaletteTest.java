package io.github.fijeastudio.artellis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PaletteTest {

    private static float luminance(int c) {
        return 0.2126f * ((c >> 16) & 0xFF) + 0.7152f * ((c >> 8) & 0xFF) + 0.0722f * (c & 0xFF);
    }

    @Test
    public void wallStaysDarkEnoughForWhiteText() {
        for (float hour = 0f; hour < 24f; hour += 0.25f) {
            int[] c = Palette.at(hour);
            assertTrue("too light at " + hour, luminance(c[0]) < 100f);
            assertTrue("edge lighter than centre at " + hour, luminance(c[1]) <= luminance(c[0]));
        }
    }

    @Test
    public void nightHasNoBlueCast() {
        int[] c = Palette.at(23f);
        assertTrue((c[0] & 0xFF) <= ((c[0] >> 16) & 0xFF));
    }

    @Test
    public void warmLightIsFullInsideTheChosenHours() {
        // Default 21:00 to 07:00, fading over the 30 minutes before and after.
        assertEquals(0f, Palette.night(12f), 0f);
        assertEquals(0f, Palette.night(20.5f), 0f);
        assertEquals(0.5f, Palette.night(20.75f), 0.01f);
        assertEquals(1f, Palette.night(21f), 0f);
        assertEquals(1f, Palette.night(3f), 0f);
        assertEquals(1f, Palette.night(7f), 0f);
        assertEquals(0.5f, Palette.night(7.25f), 0.01f);
        assertEquals(0f, Palette.night(7.5f), 0f);
    }

    @Test
    public void warmLightWindowCanSitOnOneSideOfMidnight() {
        int start = 1 * 60, end = 5 * 60 + 30;   // 01:00 to 05:30
        assertEquals(0f, Palette.night(23f, start, end), 0f);
        assertEquals(1f, Palette.night(3f, start, end), 0f);
        assertEquals(0.5f, Palette.night(5.75f, start, end), 0.01f);
        assertEquals(0.5f, Palette.night(0.75f, start, end), 0.01f);
    }

    @Test
    public void timesStepInHalfHoursAndWrap() {
        assertEquals(21 * 60 + 30, Palette.step(21 * 60, 1));
        assertEquals(0, Palette.step(23 * 60 + 30, 1));
        assertEquals(23 * 60 + 30, Palette.step(0, -1));
    }

    @Test
    public void warmLightAlsoDimsAFewPercent() {
        assertEquals(1f, Palette.dim(0f), 0f);
        assertEquals(0.85f, Palette.dim(1f), 0.001f);
        float[] m = Palette.warm(1f);
        assertEquals(0.50f * 0.85f, m[12], 0.001f);   // blue: half, then 15% dimmer
        assertEquals(0.82f * 0.85f, m[6], 0.001f);    // green
    }

    @Test
    public void warmFilterCutsBlueMost() {
        float[] m = Palette.warm(1f);
        assertTrue(m[12] < m[6] && m[6] < m[0]);
        assertEquals(1f, Palette.warm(0f)[12], 0f);
    }
}
