package com.pegasuscorp.orbe.sync;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class DriveSyncStatusTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        DriveSyncPrefs.clear(ctx);
        DriveSyncStateStore.resetForTests(ctx);
        SyncJournal.resetForTests(ctx);
    }

    @After
    public void tearDown() {
        DriveSyncPrefs.clear(ctx);
        DriveSyncStateStore.resetForTests(ctx);
    }

    @Test
    public void notConfigured_whenNoFolder() {
        assertEquals(DriveSyncStatus.Level.NOT_CONFIGURED,
                DriveSyncStatus.current(ctx).level);
    }

    @Test
    public void pending_whenJournalAheadOfExport() throws Exception {
        DriveSyncPrefs.setFolderUri(ctx, android.net.Uri.parse("content://test/tree/1"));
        SyncJournal.get(ctx).appendNotepadUpsert("id-1",
                new org.json.JSONObject().put("text", "x"));
        assertEquals(DriveSyncStatus.Level.PENDING,
                DriveSyncStatus.current(ctx).level);
    }
}
