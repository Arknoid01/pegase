package com.pegasuscorp.orbe.rag;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileInputStream;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Pont Phase 1+2 : embedding réel → VectorStore → recherche sémantique.
 */
@RunWith(RobolectricTestRunner.class)
public class RagSemanticSearchTest {

    private EmbeddingEngine engine;
    private VectorStore store;

    @Before
    public void setUp() throws Exception {
        File model = new File("src/main/assets/rag/all-MiniLM-L6-v2.onnx");
        File vocab = new File("src/main/assets/rag/vocab.txt");
        assertTrue(model.exists());
        try (FileInputStream vin = new FileInputStream(vocab)) {
            engine = EmbeddingEngine.createFromFiles(model, vin);
        }
        store = new VectorStore(EmbeddingEngine.DIMENSIONS, true);
        store.clear();
    }

    @After
    public void tearDown() {
        store.close();
        EmbeddingEngine.resetForTests();
    }

    @Test
    public void frigot_findsChambreFroide() throws Exception {
        index("projects", "Chambre froide / stock viande et DLC");
        index("projects", "Livraison Le Saloir palette porc lundi");
        index("prefs", "Playlist Spotify électro le matin");

        float[] q = engine.embed("frigot");
        List<VectorStore.Hit> hits = store.search(q, 3, 0.2f);

        assertFalse(hits.isEmpty());
        String topKey = hits.get(0).memoryKey;
        String coldKey = VectorStore.keyFor("projects", "Chambre froide / stock viande et DLC");
        assertEquals("frigot devrait remonter la chambre froide en tête", coldKey, topKey);
        System.out.println("RAG frigot → " + hits.get(0).memoryKey + " score=" + hits.get(0).score);
    }

    private void index(String category, String content) throws Exception {
        String key = VectorStore.keyFor(category, content);
        store.upsert(key, engine.embed(content));
    }
}
