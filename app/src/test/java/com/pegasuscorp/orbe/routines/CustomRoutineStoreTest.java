package com.pegasuscorp.orbe.routines;

import android.content.Context;

import com.pegasuscorp.orbe.fs.PegaseFileSystem;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class CustomRoutineStoreTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        CustomRoutineStore.resetInstanceForTests();
        PegaseFileSystem.resetInstanceForTests();
        wipe();
    }

    @After
    public void tearDown() {
        wipe();
        CustomRoutineStore.resetInstanceForTests();
        PegaseFileSystem.resetInstanceForTests();
    }

    private void wipe() {
        CustomRoutineStore store = CustomRoutineStore.getInstance(ctx);
        for (CustomRoutineStore.CustomRoutine r :
                new java.util.ArrayList<>(store.listAll())) {
            store.remove(r.id);
        }
    }

    @Test
    public void crud_toggleAndDelete() {
        CustomRoutineStore store = CustomRoutineStore.getInstance(ctx);
        CustomRoutineStore.CustomRoutine r = store.add(
                CustomRoutineStore.Type.WEB_SEARCH, "F1 résultats", null, null);
        assertTrue(r.active);
        assertTrue(store.setActive(r.id, false));
        assertFalse(store.listAll().get(0).active);
        assertTrue(store.remove(r.id));
        assertTrue(store.listAll().isEmpty());
    }

    @Test
    public void parseAddVoice_webSearch() {
        CustomRoutineStore.Draft d = CustomRoutineStore.parseAddVoice(
                "ajoute à ma routine du matin : cherche les résultats F1");
        assertNotNull(d);
        assertEquals(CustomRoutineStore.Type.WEB_SEARCH, d.type);
        assertTrue(d.query.toLowerCase().contains("f1"));
    }

    @Test
    public void ttlExpired_removedSilently() {
        CustomRoutineStore store = CustomRoutineStore.getInstance(ctx);
        long old = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10);
        store.add(CustomRoutineStore.Type.REMINDER, "x", "Expirée", 3, old);
        store.add(CustomRoutineStore.Type.REMINDER, "y", "Active", 30);

        int removed = store.purgeExpired();
        assertEquals(1, removed);
        assertEquals(1, store.listAll().size());
        assertEquals("Active", store.listAll().get(0).label);
    }

    @Test
    public void persistsAcrossReset() {
        CustomRoutineStore.getInstance(ctx).add(
                CustomRoutineStore.Type.LOAD_CONTEXT, "orion", "Contexte Orion", null);
        CustomRoutineStore.resetInstanceForTests();
        assertEquals(1, CustomRoutineStore.getInstance(ctx).listAll().size());
        assertEquals(CustomRoutineStore.Type.LOAD_CONTEXT,
                CustomRoutineStore.getInstance(ctx).listAll().get(0).type);
    }
}
