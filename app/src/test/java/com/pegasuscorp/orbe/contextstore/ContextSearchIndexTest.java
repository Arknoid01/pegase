package com.pegasuscorp.orbe.contextstore;

import android.content.Context;

import com.pegasuscorp.orbe.memory.MemoryEditResult;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Phase 2 : recherche sémantique dans les contextes .md.
 */
@RunWith(RobolectricTestRunner.class)
public class ContextSearchIndexTest {

    private static EmbeddingEngine sharedEngine;
    private ContextualFileStore store;
    private ContextSearchIndex index;
    private Context ctx;

    @BeforeClass
    public static void loadEngineOnce() throws Exception {
        ContextSearchIndex.setAutoIndexForTests(false);
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
        ContextualFileStore.resetInstanceForTests();
        ContextSearchIndex.resetInstanceForTests();
        ContextSearchIndex.setAutoIndexForTests(true);
        EmbeddingEngine.resetForTests();
        sharedEngine = null;
    }

    @Before
    public void setUp() {
        ContextSearchIndex.setAutoIndexForTests(false);
        ContextualFileStore.resetInstanceForTests();
        ContextSearchIndex.resetInstanceForTests();
        EmbeddingEngine.installForTests(sharedEngine);

        ctx = RuntimeEnvironment.getApplication();
        File dir = new File(ctx.getFilesDir(), "contexts");
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
        }

        store = ContextualFileStore.getInstance(ctx);
        index = ContextSearchIndex.getInstance(ctx);

        store.writeForTests("orion-context.md",
                "# Orion\n\n## Décisions prises\n"
                        + "- Tavily et TavilyTool pour la recherche web, exclude_domains configuré\n"
                        + "- Token Bearer pour Ollama\n");
        store.writeForTests("pegase-context.md",
                "# Pegase\n\n## Stack\n- clé Tavily dans ApiKeyStore, timeout 12s\n");
        store.writeForTests("fableris-context.md",
                "# Fableris\n\n## Notes\n- City builder, pas de web search\n");

        assertTrue(index.indexAllNow() >= 3);
    }

    @After
    public void tearDown() {
        ContextualFileStore.resetInstanceForTests();
        ContextSearchIndex.resetInstanceForTests();
    }

    @Test
    public void chunkMarkdown_splitsOnHeadings() {
        List<String> chunks = ContextSearchIndex.chunkMarkdown(
                "# Title\n\n## A\nxxx\n\n## B\nyyy\n");
        assertEquals(3, chunks.size());
        assertTrue(chunks.get(1).contains("## A"));
        assertTrue(chunks.get(2).contains("## B"));
    }

    @Test
    public void search_tavily_findsOrionAndPegase() {
        List<ContextSearchIndex.Hit> hits =
                index.search("Tavily", 5, 0.22f);
        assertFalse("aucun hit pour Tavily", hits.isEmpty());
        boolean hasOrion = false;
        boolean hasPegase = false;
        for (ContextSearchIndex.Hit h : hits) {
            System.out.println("  hit " + h.filename + " score=" + h.score);
            if (h.filename.contains("orion")) hasOrion = true;
            if (h.filename.contains("pegase")) hasPegase = true;
        }
        assertTrue("devrait trouver orion ou pegase", hasOrion || hasPegase);
        assertFalse("fableris ne devrait pas être seul en tête sans Tavily",
                hits.get(0).filename.contains("fableris"));
        System.out.println("Tavily top → " + hits.get(0).filename
                + " score=" + hits.get(0).score
                + " orion=" + hasOrion + " pegase=" + hasPegase);
    }

    @Test
    public void voiceEditor_chercheDansMesFichiers() {
        ContextEditor editor = new ContextEditor(ctx);
        assertTrue(ContextEditor.looksLikeContextCommand(
                "cherche dans mes fichiers ce qui parle de Tavily"));
        AtomicReference<MemoryEditResult> ref = new AtomicReference<>();
        editor.process("cherche dans mes fichiers ce qui parle de Tavily", ref::set);
        MemoryEditResult r = ref.get();
        assertNotNull(r);
        assertTrue(r.success);
        assertNotNull(r.spokenReply);
        assertTrue(r.spokenReply.toLowerCase().contains("orion")
                || r.spokenReply.toLowerCase().contains("tavily")
                || r.spokenReply.toLowerCase().contains("trouv"));
        System.out.println("Voice search → " + r.spokenReply);
    }
}
