package com.pegasuscorp.orbe.orion.search;

import android.content.Context;

import com.pegasuscorp.orbe.orion.OrionProjectStore;
import com.pegasuscorp.orbe.rag.EmbeddingEngine;
import com.pegasuscorp.orbe.rag.VectorStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.util.Optional;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class OrionFileSearcherTest {

    private Context ctx;
    private OrionProjectStore store;
    private VectorStore vectors;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        EmbeddingEngine.resetForTests();
        OrionProjectStore.resetInstanceForTests();
        store = OrionProjectStore.get(ctx);
        File root = store.getProjectsRoot();
        File[] kids = root.listFiles();
        if (kids != null) {
            for (File f : kids) deleteRecursive(f);
        }
        OrionProjectStore.resetInstanceForTests();
        store = OrionProjectStore.get(ctx);
        vectors = new VectorStore(EmbeddingEngine.DIMENSIONS, true);
    }

    @After
    public void tearDown() {
        vectors.clear();
        vectors.close();
    }

    @Test
    public void find_particleCount_returnsTargetLineAndSnippet() {
        store.createProject("balle-html");
        store.saveFile("ball.js",
                "function initParticles() {\n"
                        + "  let x = 1;\n"
                        + "  let particleCount = 50;\n"
                        + "  return particleCount;\n"
                        + "}\n",
                false, false);

        OrionFileSearcher searcher = new OrionFileSearcher(store, vectors);
        Optional<FileLocation> found = searcher.find("balle-html", "particleCount");

        assertTrue(found.isPresent());
        assertEquals("ball.js", found.get().filename);
        assertEquals(3, found.get().line);
        assertTrue(found.get().snippet.contains("particleCount = 50"));
        assertTrue(found.get().snippet.contains("-> "));
    }

    @Test
    public void find_particules_fallsBackLexicallyToSameFile() {
        store.createProject("balle-html");
        store.saveFile("ball.js",
                "function initParticles() {\n"
                        + "  // plus de particules ici\n"
                        + "  let particleCount = 50;\n"
                        + "}\n",
                false, false);

        OrionFileSearcher searcher = new OrionFileSearcher(store, vectors);
        Optional<FileLocation> found = searcher.find("balle-html", "particules");

        assertTrue(found.isPresent());
        assertEquals("ball.js", found.get().filename);
        assertTrue(found.get().line > 0);
    }

    @Test
    public void find_missingProjectFile_returnsEmpty() {
        store.createProject("empty");
        OrionFileSearcher searcher = new OrionFileSearcher(store, vectors);
        assertFalse(searcher.find("empty", "particleCount").isPresent());
    }

    @Test
    public void find_emptyKeyword_returnsEmpty() {
        store.createProject("demo");
        store.saveFile("index.html", "<div>hello</div>\n", false, false);

        OrionFileSearcher searcher = new OrionFileSearcher(store, vectors);
        assertFalse(searcher.find("demo", "").isPresent());
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
