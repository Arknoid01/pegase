package com.pegasuscorp.orbe.voice;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class KwsDiagnosticsTest {

    @Test
    public void computeRmsDb_silenceReturnsFloor() {
        short[] silent = new short[160];
        assertEquals(-96f, KwsDiagnostics.computeRmsDb(silent, silent.length), 0.01f);
    }

    @Test
    public void computeRmsDb_fullScaleIsNearZeroDb() {
        short[] loud = new short[100];
        for (int i = 0; i < loud.length; i++) {
            loud[i] = (short) (i % 2 == 0 ? 16_000 : -16_000);
        }
        float db = KwsDiagnostics.computeRmsDb(loud, loud.length);
        assertTrue(db > -6f);
        assertTrue(db <= 0f);
    }

    @Test
    public void computeRmsDb_nullOrEmptyIsFloor() {
        assertEquals(-96f, KwsDiagnostics.computeRmsDb(null, 0), 0.01f);
        assertEquals(-96f, KwsDiagnostics.computeRmsDb(new short[4], 0), 0.01f);
    }
}
