package com.pegasuscorp.orbe.memory;

import android.content.Context;

import com.pegasuscorp.orbe.rag.EmbeddingEngine;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Phase 3 : MemoryRepository.getRelevantMemoriesSemantic — frigot → chambre froide.
 */
@RunWith(RobolectricTestRunner.class)
public class MemoryRepositorySemanticTest {

    private static EmbeddingEngine sharedEngine;
    private MemoryRepository repo;

    @BeforeClass
    public static void loadEngineOnce() throws Exception {
        MemoryRepository.setAutoMigrateForTests(false);
        File model = new File("src/main/assets/rag/all-MiniLM-L6-v2.onnx");
        File vocab = new File("src/main/assets/rag/vocab.txt");
        assertTrue(model.exists());
        try (FileInputStream vin = new FileInputStream(vocab)) {
            sharedEngine = EmbeddingEngine.createFromFiles(model, vin);
        }
        EmbeddingEngine.installForTests(sharedEngine);
    }

    @AfterClass
    public static void unloadEngine() {
        MemoryRepository.resetInstanceForTests();
        MemoryRepository.setAutoMigrateForTests(true);
        EmbeddingEngine.resetForTests();
        sharedEngine = null;
    }

    @Before
    public void setUp() {
        MemoryRepository.setAutoMigrateForTests(false);
        MemoryRepository.resetInstanceForTests();
        EmbeddingEngine.installForTests(sharedEngine);

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
        repo.addPermanentMemory(new MemoryEntry(
                "projects", "Chambre froide / stock viande et DLC", 0.9, "2026-01-01"));
        repo.addPermanentMemory(new MemoryEntry(
                "projects", "Livraison Le Saloir palette porc lundi", 0.8, "2026-01-01"));
        repo.addPermanentMemory(new MemoryEntry(
                "prefs", "Playlist Spotify électro le matin", 0.7, "2026-01-01"));
        assertEquals(3, repo.getAllPermanentMemories().size());
    }

    @Test
    public void frigot_findsChambreFroideViaRepository() {
        List<MemoryEntry> hits = repo.getRelevantMemoriesSemantic(
                "frigot", 3, MemoryRepository.SEMANTIC_MIN_SCORE);

        assertFalse("aucun souvenir sémantique pour frigot", hits.isEmpty());
        assertTrue(
                "frigot devrait remonter la chambre froide: " + hits.get(0).content,
                hits.get(0).content.toLowerCase().contains("chambre froide"));
        System.out.println("MemoryRepo frigot → " + hits.get(0).content);
    }

    @Test
    public void keywordFallback_stillWorksWhenQueryExact() {
        List<MemoryEntry> hits = repo.getRelevantMemories("spotify", null, 2, 0.0);
        assertFalse(hits.isEmpty());
        assertTrue(hits.get(0).content.toLowerCase().contains("spotify"));
    }
}
