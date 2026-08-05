package com.pegasuscorp.orbe.memory;

import android.content.Context;

import com.pegasuscorp.orbe.rag.EmbeddingEngine;

import org.junit.After;
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

@RunWith(RobolectricTestRunner.class)
public class MemoryUpdateJudgeTest {

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
        MemoryUpdateJudge.setOverrideForTests(null);
        MemoryUpdateJudge.setEnabledForTests(true);

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

    @After
    public void tearDown() {
        MemoryUpdateJudge.setOverrideForTests(null);
        MemoryUpdateJudge.setEnabledForTests(true);
    }

    @Test
    public void delete_softInvalidatesAndAdds() {
        repo.addPermanentMemory(new MemoryEntry(
                "session", "Réunion Fableris vendredi 15h", 0.9, "2026-01-01"));
        MemoryUpdateJudge.setOverrideForTests((fact, cat, neighbors) ->
                new MemoryUpdateDecision(MemoryUpdateDecision.Op.DELETE, 0, null,
                        "heure changée"));

        assertTrue(MemoryUpdateJudge.judgeAndApply(
                ctx, repo, "Réunion Fableris vendredi 17h", "session", 0.72, "2026-08-05"));

        List<MemoryEntry> all = repo.getAllPermanentMemories();
        assertEquals(2, all.size());
        MemoryEntry old = null;
        MemoryEntry neu = null;
        for (MemoryEntry e : all) {
            if (e.content.contains("15h")) old = e;
            if (e.content.contains("17h")) neu = e;
        }
        assertNotNull(old);
        assertNotNull(neu);
        assertTrue(old.isInvalid());
        assertFalse(neu.isInvalid());
        assertFalse(MemoryRepository.isInjectable(old));
        assertTrue(MemoryRepository.isInjectable(neu));
        assertTrue(old.supersededByKey.contains("17h")
                || old.supersededByKey.equals(neu.memoryKey()));
    }

    @Test
    public void update_keepsPreviousContent() {
        repo.addPermanentMemory(new MemoryEntry(
                "session", "Yannick préfère le café", 0.8, "2026-01-01"));
        MemoryUpdateJudge.setOverrideForTests((fact, cat, neighbors) ->
                new MemoryUpdateDecision(MemoryUpdateDecision.Op.UPDATE, 0,
                        "Yannick préfère le thé vert", "changement"));

        assertTrue(MemoryUpdateJudge.judgeAndApply(
                ctx, repo, "Yannick préfère le thé", "session", 0.72, "2026-08-05"));

        List<MemoryEntry> all = repo.getAllPermanentMemories();
        assertEquals(1, all.size());
        MemoryEntry e = all.get(0);
        assertEquals("Yannick préfère le thé vert", e.content);
        assertEquals("Yannick préfère le café", e.previousContent);
        assertFalse(e.isInvalid());
    }

    @Test
    public void invalid_excludedFromSemanticRetrieve() {
        MemoryEntry stale = new MemoryEntry(
                "session", "RDV dentiste lundi 10h", 0.9, "2026-01-01");
        repo.addPermanentMemory(stale);
        repo.invalidateMemory(stale, "reporté", "");

        MemoryEntry fresh = new MemoryEntry(
                "session", "RDV dentiste mardi 14h", 0.9, "2026-08-05");
        repo.addPermanentMemory(fresh);

        List<MemoryEntry> hits = repo.getRelevantMemoriesSemantic("dentiste", 5, 0.2f);
        for (MemoryEntry h : hits) {
            assertFalse(h.isInvalid());
            assertFalse(h.content.contains("lundi 10h"));
        }
        assertFalse(hits.isEmpty());
    }

    @Test
    public void entry_roundTripInvalidFields() throws Exception {
        MemoryEntry e = new MemoryEntry("pending", "Rappel courses", 0.6, "2026-08-05");
        e.previousContent = "Rappel courses 18h";
        e.invalidAtMs = 12345L;
        e.invalidReason = "fait";
        e.supersededByKey = "abc";
        MemoryEntry back = MemoryEntry.fromJson(e.toJson());
        assertEquals(12345L, back.invalidAtMs);
        assertEquals("fait", back.invalidReason);
        assertEquals("abc", back.supersededByKey);
        assertEquals("Rappel courses 18h", back.previousContent);
        assertTrue(back.isInvalid());
    }
}
