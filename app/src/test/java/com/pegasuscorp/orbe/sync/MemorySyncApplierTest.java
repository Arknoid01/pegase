package com.pegasuscorp.orbe.sync;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.pegasuscorp.orbe.fs.PegaseFileSystem;
import com.pegasuscorp.orbe.memory.MemoryEntry;
import com.pegasuscorp.orbe.memory.MemoryRepository;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class MemorySyncApplierTest {

    private Context ctx;
    private MemoryRepository repo;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        PegaseFileSystem.resetInstanceForTests();
        SyncJournal.resetForTests(ctx);
        MemoryRepository.setAutoMigrateForTests(false);
        MemoryRepository.resetInstanceForTests();
        File memDir = new File(ctx.getFilesDir(), "memory");
        if (memDir.exists()) {
            File[] files = memDir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
        }
        repo = MemoryRepository.getInstance(ctx);
        while (!repo.getAllPermanentMemories().isEmpty()) {
            repo.removePermanentMemoryAt(0);
        }
    }

    @After
    public void tearDown() {
        MemoryRepository.resetInstanceForTests();
        SyncJournal.resetForTests(ctx);
        MemoryRepository.setAutoMigrateForTests(true);
    }

    @Test
    public void remoteUpsert_insertsWhenMissing() throws Exception {
        JSONObject payload = new JSONObject()
                .put("id", "mem-remote-1")
                .put("category", "general")
                .put("content", "from pc")
                .put("importance", 0.7)
                .put("createdAt", "2026-08-10")
                .put("updatedAtMs", 1000);
        SyncOp op = new SyncOp("op-m1", 1, 1000, "pc", SyncOp.STORE_MEMORY,
                SyncOp.OP_UPSERT, "mem-remote-1", payload);

        assertTrue(MemorySyncApplier.applyOne(repo, op));
        assertEquals(1, repo.getAllPermanentMemories().size());
        assertEquals("from pc", repo.getAllPermanentMemories().get(0).content);
    }

    @Test
    public void lww_remoteOlderDoesNotWin() throws Exception {
        MemoryEntry local = new MemoryEntry("general", "local wins", 0.5, "2026-08-10");
        repo.addPermanentMemory(local);
        long localTs = local.updatedAtMs;

        JSONObject payload = new JSONObject()
                .put("id", local.id)
                .put("category", "general")
                .put("content", "stale remote")
                .put("importance", 0.5)
                .put("createdAt", "2026-08-10")
                .put("updatedAtMs", localTs - 1000);
        SyncOp op = new SyncOp("op-m2", 2, localTs - 1000, "pc", SyncOp.STORE_MEMORY,
                SyncOp.OP_UPSERT, local.id, payload);

        assertFalse(MemorySyncApplier.applyOne(repo, op));
        MemoryEntry kept = repo.getAllPermanentMemories().stream()
                .filter(e -> local.id.equals(e.id))
                .findFirst()
                .orElseThrow();
        assertEquals("local wins", kept.content);
    }

    @Test
    public void remoteDelete_removesWhenNewer() throws Exception {
        MemoryEntry entry = new MemoryEntry("general", "to delete", 0.5, "2026-08-10");
        repo.addPermanentMemory(entry);
        long ts = System.currentTimeMillis() + 5000;

        SyncOp op = new SyncOp("op-m3", 3, ts, "pc", SyncOp.STORE_MEMORY,
                SyncOp.OP_DELETE, entry.id, null);

        assertTrue(MemorySyncApplier.applyOne(repo, op));
        assertNull(repo.getAllPermanentMemories().stream()
                .filter(e -> entry.id.equals(e.id))
                .findFirst()
                .orElse(null));
    }
}
