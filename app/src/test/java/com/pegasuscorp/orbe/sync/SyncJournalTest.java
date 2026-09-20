package com.pegasuscorp.orbe.sync;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.pegasuscorp.orbe.fs.PegaseFileSystem;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class SyncJournalTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        PegaseFileSystem.resetInstanceForTests();
        SyncDevice.resetForTests(ctx);
        SyncJournal.resetForTests(ctx);
    }

    @After
    public void tearDown() {
        SyncJournal.resetForTests(ctx);
    }

    @Test
    public void appendAndOpsSince_returnsNewOpsOnly() throws Exception {
        SyncJournal journal = SyncJournal.get(ctx);
        SyncOp first = journal.appendNotepadUpsert("id-1", new JSONObject().put("text", "a"));
        SyncOp second = journal.appendNotepadDelete("id-2");

        List<SyncOp> all = journal.opsSince(SyncCursor.empty());
        assertEquals(2, all.size());
        assertEquals(first.opId, all.get(0).opId);
        assertEquals(second.opId, all.get(1).opId);

        List<SyncOp> sinceFirst = journal.opsSince(new SyncCursor(first.seq, first.opId, first.tsMs));
        assertEquals(1, sinceFirst.size());
        assertEquals(second.opId, sinceFirst.get(0).opId);
    }

    @Test
    public void latestCursor_tracksHead() throws Exception {
        SyncJournal journal = SyncJournal.get(ctx);
        journal.appendNotepadUpsert("id-1", new JSONObject().put("text", "a"));
        SyncOp last = journal.appendNotepadDelete("id-1");

        SyncCursor head = journal.latestCursor();
        assertEquals(last.seq, head.lastSeq);
        assertEquals(last.opId, head.lastOpId);
    }
}
