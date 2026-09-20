package com.pegasuscorp.orbe.sync;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.pegasuscorp.orbe.fs.PegaseFileSystem;
import com.pegasuscorp.orbe.notepad.NotepadStore;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class NotepadSyncApplierTest {

    private Context ctx;
    private NotepadStore store;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        PegaseFileSystem.resetInstanceForTests();
        SyncJournal.resetForTests(ctx);
        NotepadStore.resetInstanceForTests();
        store = NotepadStore.getInstance(ctx);
    }

    @After
    public void tearDown() {
        NotepadStore.resetInstanceForTests();
        SyncJournal.resetForTests(ctx);
    }

    @Test
    public void remoteUpsert_insertsWhenMissing() throws Exception {
        JSONObject payload = new JSONObject()
                .put("id", "remote-1")
                .put("text", "from pc")
                .put("created", "2026-08-10")
                .put("updatedAtMs", 1000);
        SyncOp op = new SyncOp("op-1", 1, 1000, "pc", SyncOp.STORE_NOTEPAD,
                SyncOp.OP_UPSERT, "remote-1", payload);

        assertTrue(NotepadSyncApplier.applyOne(store, op));
        assertEquals(1, store.getActiveItems().size());
        assertEquals("from pc", store.getActiveItems().get(0).text);
    }

    @Test
    public void lww_remoteOlderDoesNotWin() throws Exception {
        store.add("local wins");
        String id = store.getActiveItems().get(0).id;
        long localTs = store.getActiveItems().get(0).updatedAtMs;

        JSONObject payload = new JSONObject()
                .put("id", id)
                .put("text", "stale remote")
                .put("updatedAtMs", localTs - 1000);
        SyncOp op = new SyncOp("op-2", 2, localTs - 1000, "pc", SyncOp.STORE_NOTEPAD,
                SyncOp.OP_UPSERT, id, payload);

        assertFalse(NotepadSyncApplier.applyOne(store, op));
        assertEquals("local wins", store.getActiveItems().get(0).text);
    }

    @Test
    public void remoteDelete_removesWhenNewer() throws Exception {
        store.add("to delete");
        String id = store.getActiveItems().get(0).id;
        long ts = System.currentTimeMillis() + 5000;

        SyncOp op = new SyncOp("op-3", 3, ts, "pc", SyncOp.STORE_NOTEPAD,
                SyncOp.OP_DELETE, id, null);

        assertTrue(NotepadSyncApplier.applyOne(store, op));
        assertTrue(store.getActiveItems().isEmpty());
    }
}
