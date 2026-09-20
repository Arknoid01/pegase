package com.pegasuscorp.orbe.sync;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.pegasuscorp.orbe.fs.PegaseFileSystem;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class DriveSyncStateStoreTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        PegaseFileSystem.resetInstanceForTests();
        DriveSyncStateStore.resetForTests(ctx);
    }

    @After
    public void tearDown() {
        DriveSyncStateStore.resetForTests(ctx);
    }

    @Test
    public void tracksExportedSeqAndAppliedOps() {
        DriveSyncStateStore state = DriveSyncStateStore.get(ctx);
        state.setLastExportedSeq(3);
        state.markRemoteOpApplied("op-a");

        DriveSyncStateStore.resetInstanceForTests();
        DriveSyncStateStore reloaded = DriveSyncStateStore.get(ctx);
        assertEquals(3, reloaded.getLastExportedSeq());
        assertTrue(reloaded.isRemoteOpApplied("op-a"));
        assertFalse(reloaded.isRemoteOpApplied("op-b"));
    }
}
