package com.pegasuscorp.orbe.rag;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;

import static org.junit.Assert.*;

/**
 * Phase 1 RAG — embedding local ONNX (ORT desktop en unit test).
 */
public class EmbeddingEngineTest {

    private EmbeddingEngine engine;

    @Before
    public void setUp() throws Exception {
        File model = new File("src/main/assets/rag/all-MiniLM-L6-v2.onnx");
        File vocab = new File("src/main/assets/rag/vocab.txt");
        assertTrue("Modèle ONNX manquant dans assets/rag/", model.exists());
        assertTrue(vocab.exists());
        try (FileInputStream vin = new FileInputStream(vocab)) {
            engine = EmbeddingEngine.createFromFiles(model, vin);
        }
    }

    @After
    public void tearDown() {
        EmbeddingEngine.resetForTests();
    }

    @Test
    public void embed_bonjour_returns384Normalized() throws Exception {
        long t0 = System.currentTimeMillis();
        float[] v = engine.embed("bonjour");
        long ms = System.currentTimeMillis() - t0;

        assertEquals(EmbeddingEngine.DIMENSIONS, v.length);
        double norm = 0;
        for (float x : v) norm += (double) x * x;
        assertEquals(1.0, Math.sqrt(norm), 1e-4);

        System.out.println("EmbeddingEngine loadMs=" + engine.getLoadMs()
                + " embed(\"bonjour\")=" + ms + " ms"
                + " first3=[" + v[0] + ", " + v[1] + ", " + v[2] + "]");
        assertTrue("embedding trop lent: " + ms + " ms", ms < 5_000);
    }

    @Test
    public void embed_similarFrenchWords_highCosine() throws Exception {
        float[] a = engine.embed("chambre froide");
        float[] b = engine.embed("frigo");
        float[] c = engine.embed("playlist spotify");
        float simClose = VectorMath.cosineSimilarity(a, b);
        float simFar = VectorMath.cosineSimilarity(a, c);
        System.out.println("cosine(chambre froide, frigo)=" + simClose
                + " cosine(chambre froide, playlist)=" + simFar);
        assertTrue("frigo devrait être plus proche de chambre froide que playlist",
                simClose > simFar);
    }
}
