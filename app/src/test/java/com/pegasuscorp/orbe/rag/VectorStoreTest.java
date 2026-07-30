package com.pegasuscorp.orbe.rag;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class VectorStoreTest {

    private VectorStore store;

    @Before
    public void setUp() {
        store = new VectorStore(8, true);
        store.clear();
    }

    @After
    public void tearDown() {
        store.clear();
        store.close();
    }

    @Test
    public void upsert_and_hasVector() {
        float[] v = unit(1, 0, 0, 0, 0, 0, 0, 0);
        store.upsert("k1", v);
        assertTrue(store.hasVector("k1"));
        assertEquals(1, store.size());
        store.upsert("k1", unit(0, 1, 0, 0, 0, 0, 0, 0));
        assertEquals(1, store.size());
    }

    @Test
    public void search_returnsNearestByCosine() {
        store.upsert("froid", unit(1, 0, 0, 0, 0, 0, 0, 0));
        store.upsert("frigo", unit(0.9f, 0.1f, 0, 0, 0, 0, 0, 0));
        store.upsert("musique", unit(0, 0, 0, 0, 0, 0, 0, 1));
        store.upsert("playlist", unit(0, 0, 0, 0, 0, 0.2f, 0, 0.8f));

        float[] query = unit(1, 0, 0, 0, 0, 0, 0, 0);
        List<VectorStore.Hit> hits = store.search(query, 3, 0f);

        assertEquals(3, hits.size());
        assertEquals("froid", hits.get(0).memoryKey);
        assertEquals("frigo", hits.get(1).memoryKey);
        assertTrue(hits.get(0).score > hits.get(1).score);
        assertTrue(hits.get(1).score > hits.get(2).score);
        assertNotEquals("musique", hits.get(0).memoryKey);
    }

    @Test
    public void search_respectsMinScoreAndTopK() {
        for (int i = 0; i < 10; i++) {
            float[] v = new float[8];
            v[0] = 1f - i * 0.05f;
            v[1] = i * 0.05f;
            store.upsert("m" + i, VectorMath.l2Normalize(v));
        }
        float[] query = unit(1, 0, 0, 0, 0, 0, 0, 0);
        List<VectorStore.Hit> hits = store.search(query, 5, 0.85f);
        assertTrue(hits.size() <= 5);
        for (VectorStore.Hit h : hits) {
            assertTrue(h.score >= 0.85f - 1e-5f);
        }
    }

    @Test
    public void keyFor_stable() {
        String a = VectorStore.keyFor("projects", "Chambre froide / frigot");
        String b = VectorStore.keyFor("projects", "Chambre froide / frigot");
        String c = VectorStore.keyFor("projects", "Autre chose");
        assertEquals(a, b);
        assertNotEquals(a, c);
    }

    @Test
    public void delete_removesRow() {
        store.upsert("x", unit(1, 0, 0, 0, 0, 0, 0, 0));
        store.delete("x");
        assertFalse(store.hasVector("x"));
        assertEquals(0, store.size());
    }

    @Test
    public void namespace_isolatesDiagFromDefaultSearch() {
        store.upsert("memory-a", unit(1, 0, 0, 0, 0, 0, 0, 0));
        store.upsert("diag-a", unit(1, 0, 0, 0, 0, 0, 0, 0),
                VectorStore.NS_DIAG, "{\"tool\":\"notepad\"}");

        List<VectorStore.Hit> mem = store.search(unit(1, 0, 0, 0, 0, 0, 0, 0), 5, 0f);
        List<VectorStore.Hit> diag = store.search(unit(1, 0, 0, 0, 0, 0, 0, 0), 5, 0f,
                VectorStore.NS_DIAG);

        assertEquals(1, mem.size());
        assertEquals("memory-a", mem.get(0).memoryKey);
        assertEquals(1, diag.size());
        assertEquals("diag-a", diag.get(0).memoryKey);
        assertEquals(VectorStore.NS_DIAG, diag.get(0).namespace);
        assertNotNull(diag.get(0).payload);
    }

    @Test
    public void namespace_isolatesOrionFilesFromMemorySearch() {
        store.upsert("memory-a", unit(1, 0, 0, 0, 0, 0, 0, 0));
        store.upsert("ball.js", unit(1, 0, 0, 0, 0, 0, 0, 0),
                VectorStore.NS_ORION_FILES, "{\"project\":\"balle-html\"}");

        List<VectorStore.Hit> mem = store.search(unit(1, 0, 0, 0, 0, 0, 0, 0), 5, 0f);
        List<VectorStore.Hit> files = store.search(unit(1, 0, 0, 0, 0, 0, 0, 0), 5, 0f,
                VectorStore.NS_ORION_FILES);

        assertEquals(1, mem.size());
        assertEquals("memory-a", mem.get(0).memoryKey);
        assertEquals(1, files.size());
        assertEquals("ball.js", files.get(0).memoryKey);
        assertEquals(VectorStore.NS_ORION_FILES, files.get(0).namespace);
    }

    @Test
    public void deleteKeysWithPrefix_removesMatchingRows() {
        store.upsert("orion-code:demo:A.java#foo", unit(1, 0, 0, 0, 0, 0, 0, 0),
                VectorStore.NS_ORION_CODE, "{}");
        store.upsert("orion-code:demo:A.java#bar", unit(0, 1, 0, 0, 0, 0, 0, 0),
                VectorStore.NS_ORION_CODE, "{}");
        store.upsert("orion-code:demo:B.java#foo", unit(0, 0, 1, 0, 0, 0, 0, 0),
                VectorStore.NS_ORION_CODE, "{}");
        assertEquals(2, store.deleteKeysWithPrefix(
                VectorStore.NS_ORION_CODE, "orion-code:demo:A.java#"));
        assertEquals(1, store.size(VectorStore.NS_ORION_CODE));
    }

    @Test
    public void purgeNamespaceOlderThan_keepsRecent() {
        long old = System.currentTimeMillis() - 10L * 24 * 60 * 60 * 1000;
        long recent = System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000;
        store.upsert("old", unit(1, 0, 0, 0, 0, 0, 0, 0),
                VectorStore.NS_DIAG, null, old);
        store.upsert("new", unit(0, 1, 0, 0, 0, 0, 0, 0),
                VectorStore.NS_DIAG, null, recent);

        assertEquals(1, store.purgeNamespaceOlderThan(VectorStore.NS_DIAG, 7));
        assertFalse(store.hasVector("old"));
        assertTrue(store.hasVector("new"));
    }

    private static float[] unit(float... values) {
        return VectorMath.l2Normalize(values);
    }
}
