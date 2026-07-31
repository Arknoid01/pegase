package com.pegasuscorp.orbe.intentions.location;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class SavedPlaceStoreTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        SavedPlaceStore.resetInstanceForTests();
        SavedPlaceStore.getInstance(ctx).clearAll();
    }

    @After
    public void tearDown() {
        SavedPlaceStore.resetInstanceForTests();
    }

    @Test
    public void upsert_replacesSameType() {
        SavedPlaceStore store = SavedPlaceStore.getInstance(ctx);
        store.upsert(SavedPlace.Type.HOME, "Maison A", 48.1, 2.1, 100f);
        store.upsert(SavedPlace.Type.HOME, "Maison B", 48.2, 2.2, 100f);
        assertEquals(1, store.listAll().size());
        assertEquals("Maison B", store.listAll().get(0).label);
    }

    @Test
    public void findAt_returnsNearestInsideRadius() {
        SavedPlaceStore store = SavedPlaceStore.getInstance(ctx);
        store.upsert(SavedPlace.Type.HOME, "Maison", 48.8566, 2.3522, 150f);
        store.upsert(SavedPlace.Type.WORK, "Bureau", 48.8600, 2.3600, 150f);
        SavedPlace hit = store.findAt(48.8567, 2.3523);
        assertNotNull(hit);
        assertEquals(SavedPlace.Type.HOME, hit.type);
        assertNull(store.findAt(49.0, 3.0));
    }

    @Test
    public void distanceM_isReasonable() {
        float d = LocationSituationReader.distanceM(48.8566, 2.3522, 48.8600, 2.3600);
        assertTrue(d > 500f);
        assertTrue(d < 1500f);
    }
}
