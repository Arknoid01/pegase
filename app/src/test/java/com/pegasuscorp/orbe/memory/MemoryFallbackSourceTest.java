package com.pegasuscorp.orbe.memory;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class MemoryFallbackSourceTest {

    private MemoryRepository repo;

    @Before
    public void setUp() {
        MemoryRepository.setAutoMigrateForTests(false);
        MemoryRepository.resetInstanceForTests();
        Context ctx = RuntimeEnvironment.getApplication();
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

    @Test
    public void fallbackMemories_excludedFromRelevantContext() {
        repo.addPermanentMemory(new MemoryEntry(
                "preference", "J'aime le café le matin", 0.9, "2026-01-01",
                MemoryEntry.SOURCE_USER));
        repo.addPermanentMemory(new MemoryEntry(
                "preference", "Bug inventé de synchronisation", 0.95, "2026-01-01",
                MemoryEntry.SOURCE_FALLBACK));

        assertEquals(2, repo.getAllPermanentMemories().size());

        List<MemoryEntry> relevant = repo.getRelevantMemories("café synchronisation bug", 5);
        assertEquals(1, relevant.size());
        assertEquals("J'aime le café le matin", relevant.get(0).content);
        assertFalse(relevant.get(0).isFallbackSource());
    }

    @Test
    public void memoryEntry_persistsSource() throws Exception {
        MemoryEntry e = new MemoryEntry("p", "x", 0.5, "2026-01-01",
                MemoryEntry.SOURCE_FALLBACK);
        MemoryEntry round = MemoryEntry.fromJson(e.toJson());
        assertEquals(MemoryEntry.SOURCE_FALLBACK, round.source);
        assertTrue(round.isFallbackSource());
    }
}
