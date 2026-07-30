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
import java.util.Arrays;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class MemoryConsolidatorTest {

    private static EmbeddingEngine sharedEngine;
    private MemoryRepository repo;
    private Context ctx;

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

        ctx = RuntimeEnvironment.getApplication();
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
    public void promoteSessionFacts_addsNewFacts() {
        SessionSummary summary = new SessionSummary();
        summary.importantFacts.add("Réunion Fableris vendredi 14h");

        MemoryConsolidator.promoteSessionFacts(ctx, summary);

        assertEquals(1, repo.getAllPermanentMemories().size());
        assertEquals("session", repo.getAllPermanentMemories().get(0).category);
        assertTrue(repo.getAllPermanentMemories().get(0).content.contains("Fableris"));
    }

    @Test
    public void promoteSessionFacts_skipsTextDuplicate() {
        repo.addPermanentMemory(new MemoryEntry(
                "projects", "Réunion Fableris vendredi 14h", 0.9, "2026-01-01"));

        SessionSummary summary = new SessionSummary();
        summary.importantFacts.add("Réunion Fableris vendredi 14h");

        MemoryConsolidator.promoteSessionFacts(ctx, summary);

        assertEquals(1, repo.getAllPermanentMemories().size());
    }

    @Test
    public void addSessionSummary_triggersConsolidation() {
        SessionSummary summary = new SessionSummary();
        summary.topic = "Test";
        summary.summary = "Discussion test";
        summary.importantFacts.addAll(Arrays.asList("Nouveau fait unique XYZ", "Autre fait ABC"));

        repo.addSessionSummary(summary);

        assertEquals(2, repo.getAllPermanentMemories().size());
        assertEquals(1, repo.getAllSessionSummaries().size());
    }
}
