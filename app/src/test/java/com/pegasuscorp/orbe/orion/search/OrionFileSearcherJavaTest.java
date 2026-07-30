package com.pegasuscorp.orbe.orion.search;

import android.content.Context;

import com.pegasuscorp.orbe.orion.OrionProjectStore;
import com.pegasuscorp.orbe.rag.EmbeddingEngine;
import com.pegasuscorp.orbe.rag.VectorMath;
import com.pegasuscorp.orbe.rag.VectorStore;

import org.json.JSONObject;
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
public class OrionFileSearcherJavaTest {

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
        vectors = new VectorStore(8, true);
    }

    @After
    public void tearDown() {
        vectors.clear();
        vectors.close();
    }

    @Test
    public void find_javaProjectUsesCodeIndexerFirst() throws Exception {
        store.createProject("java-demo");
        store.saveFile("Demo.java",
                "public class Demo {\n"
                        + "  public void buildQuestionPrompt() {}\n"
                        + "  int particleCount = 1;\n"
                        + "}\n",
                false, false);

        float[] v = unit(1, 0, 0, 0, 0, 0, 0, 0);
        JSONObject payload = new JSONObject()
                .put("project", "java-demo")
                .put("filename", "Demo.java")
                .put("method", "buildQuestionPrompt")
                .put("startLine", "2")
                .put("endLine", "2")
                .put("kind", "method")
                .put("snippet", "public void buildQuestionPrompt() {}");
        vectors.upsert("orion-code:java-demo:Demo.java#buildQuestionPrompt", v,
                VectorStore.NS_ORION_CODE, payload.toString());

        OrionCodeIndexer indexer = new OrionCodeIndexer("java-demo", text -> v, vectors);
        OrionFileSearcher searcher = new OrionFileSearcher(store, vectors)
                .withCodeIndexer(indexer);

        Optional<FileLocation> found = searcher.find("java-demo", "buildQuestionPrompt");
        assertTrue(found.isPresent());
        assertEquals("Demo.java", found.get().filename);
        assertEquals(2, found.get().line);
    }

    @Test
    public void find_javaWithoutCodeHit_fallsBackEmptyForUnknownSymbol() {
        store.createProject("java-demo");
        store.saveFile("Demo.java", "public class Demo { int x = 1; }\n", false, false);

        OrionCodeIndexer indexer = new OrionCodeIndexer("java-demo",
                text -> unit(0, 1, 0, 0, 0, 0, 0, 0), vectors);
        OrionFileSearcher searcher = new OrionFileSearcher(store, vectors)
                .withCodeIndexer(indexer);

        assertFalse(searcher.find("java-demo", "particleCount").isPresent());
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

    private static float[] unit(float... values) {
        return VectorMath.l2Normalize(values);
    }
}
