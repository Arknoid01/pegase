package com.pegasuscorp.orbe.intentions.location;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class LocationSituationPrefsTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        LocationSituationPrefs.clearAll(ctx);
    }

    @Test
    public void defaults_matchTrackerConstants() {
        assertTrue(LocationSituationPrefs.isAutoDriveEnabled(ctx));
        assertEquals(20f, LocationSituationPrefs.getDriveEnterKmh(ctx), 0.01f);
        assertEquals(15f, LocationSituationPrefs.getDriveExitKmh(ctx), 0.01f);
        assertEquals(10, LocationSituationPrefs.getSpeedMaxAgeMinutes(ctx));
        assertEquals(120f, LocationSituationPrefs.getDefaultRadiusM(ctx), 0.01f);
    }

    @Test
    public void customValues_persist() {
        LocationSituationPrefs.setDriveEnterKmh(ctx, 25f);
        LocationSituationPrefs.setDriveExitKmh(ctx, 12f);
        LocationSituationPrefs.setSpeedMaxAgeMinutes(ctx, 5);
        LocationSituationPrefs.setAutoDriveEnabled(ctx, false);
        assertEquals(25f, LocationSituationPrefs.getDriveEnterKmh(ctx), 0.01f);
        assertEquals(12f, LocationSituationPrefs.getDriveExitKmh(ctx), 0.01f);
        assertEquals(5, LocationSituationPrefs.getSpeedMaxAgeMinutes(ctx));
        assertFalse(LocationSituationPrefs.isAutoDriveEnabled(ctx));
    }
}
