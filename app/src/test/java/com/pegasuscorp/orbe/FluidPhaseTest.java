package com.pegasuscorp.orbe;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class FluidPhaseTest {

    @Test
    public void forHour_middayLabel() {
        FluidPhase.State state = FluidPhase.forHour(12, 30);
        assertEquals("Midday", state.label);
    }

    @Test
    public void forHour_nightColors() {
        FluidPhase.State state = FluidPhase.forHour(2, 0);
        assertTrue(state.topColor != 0);
        assertTrue(state.blobA != 0);
    }

    @Test
    public void tinted_usesPalette() {
        FluidPhase.State base = FluidPhase.forHour(12, 0);
        OrbThemes.Palette palette = OrbThemes.ALL[4];
        FluidPhase.State tinted = FluidPhase.tinted(base, palette);
        assertNotEquals(base.blobA, tinted.blobA);
    }
}
