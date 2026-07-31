package com.pegasuscorp.orbe.intentions.location;

import android.content.Context;

import com.pegasuscorp.orbe.intentions.PegaseModeStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class LocationSituationTrackerTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        SavedPlaceStore.resetInstanceForTests();
        LocationSituationReader.clearTestOverride();
        PegaseModeStore.setMode(ctx, PegaseModeStore.Mode.NORMAL);
        SavedPlaceStore.getInstance(ctx).clearAll();
        LocationSituationReader.setCurrentPlace(ctx, null);
    }

    @After
    public void tearDown() {
        LocationSituationReader.clearTestOverride();
        SavedPlaceStore.resetInstanceForTests();
    }

    @Test
    public void speedAbove20_entersAutoDrive() {
        LocationSituationReader.setTestOverride(
                new LocationSituationReader.Snapshot(48.8, 2.3, 6.5f, System.currentTimeMillis(), true));
        LocationSituationTracker.evaluate(ctx);
        assertEquals(PegaseModeStore.Mode.DRIVE, PegaseModeStore.getMode(ctx));
        assertTrue(PegaseModeStore.isAutoDriveActive(ctx));
    }

    @Test
    public void speedBelow15_exitsAutoDrive() {
        LocationSituationReader.setTestOverride(
                new LocationSituationReader.Snapshot(48.8, 2.3, 6.5f, System.currentTimeMillis(), true));
        LocationSituationTracker.evaluate(ctx);
        assertEquals(PegaseModeStore.Mode.DRIVE, PegaseModeStore.getMode(ctx));

        LocationSituationReader.setTestOverride(
                new LocationSituationReader.Snapshot(48.8, 2.3, 3f, System.currentTimeMillis(), true));
        LocationSituationTracker.evaluate(ctx);
        assertEquals(PegaseModeStore.Mode.NORMAL, PegaseModeStore.getMode(ctx));
        assertFalse(PegaseModeStore.isAutoDriveActive(ctx));
    }

    @Test
    public void manualDrive_notClearedByLowSpeed() {
        PegaseModeStore.setMode(ctx, PegaseModeStore.Mode.DRIVE);
        assertFalse(PegaseModeStore.isAutoDriveActive(ctx));

        LocationSituationReader.setTestOverride(
                new LocationSituationReader.Snapshot(48.8, 2.3, 0f, System.currentTimeMillis(), true));
        LocationSituationTracker.evaluate(ctx);
        assertEquals(PegaseModeStore.Mode.DRIVE, PegaseModeStore.getMode(ctx));
    }

    @Test
    public void placeResolved_whenInsideRadius() {
        SavedPlaceStore.getInstance(ctx).upsert(
                SavedPlace.Type.HOME, "Maison", 48.8566, 2.3522, 200f);
        LocationSituationReader.setTestOverride(
                new LocationSituationReader.Snapshot(48.8567, 2.3523, 0f,
                        System.currentTimeMillis(), true));
        LocationSituationTracker.evaluate(ctx);
        assertEquals("maison · Maison", LocationSituationReader.getCurrentPlaceLabel(ctx));
    }
}
