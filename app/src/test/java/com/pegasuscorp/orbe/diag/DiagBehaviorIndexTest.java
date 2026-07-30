package com.pegasuscorp.orbe.diag;

import com.pegasuscorp.orbe.rag.VectorMath;
import com.pegasuscorp.orbe.rag.VectorStore;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class DiagBehaviorIndexTest {

    private VectorStore store;
    private static final int DIMS = 16;

    private final DiagBehaviorIndex.Embedder embedder = text -> bag(text, DIMS);

    @Before
    public void setUp() {
        store = new VectorStore(DIMS, true);
        store.clear();
    }

    @After
    public void tearDown() {
        store.clear();
        store.close();
    }

    @Test
    public void search_findsSimilarHesitation() throws Exception {
        JSONObject event = hesitation(
                System.currentTimeMillis(),
                "notepad",
                "params_incomplets",
                "champ text manquant",
                "ajoute projet futur au bloc-notes");
        assertTrue(DiagBehaviorIndex.indexOne(store, embedder, event));

        float[] q = embedder.embed(
                "hésitation notepad text manquant projet futur");
        List<VectorStore.Hit> hits = store.search(q, 5, 0.3f, VectorStore.NS_DIAG);
        assertFalse(hits.isEmpty());
        assertEquals(VectorStore.NS_DIAG, hits.get(0).namespace);
        assertTrue(hits.get(0).memoryKey.contains("notepad"));
        assertTrue(hits.get(0).memoryKey.contains("hesitation"));

        String answer = DiagBehaviorIndex.synthesizeSearchAnswer(
                "tu as déjà eu ce problème ?", hits);
        assertTrue(answer.toLowerCase(Locale.ROOT).contains("oui")
                || answer.toLowerCase(Locale.ROOT).contains("cas"));
        assertTrue(answer.toLowerCase(Locale.ROOT).contains("notepad"));
    }

    @Test
    public void namespace_diagIsolatedFromMemory() throws Exception {
        store.upsert("mem:projects", bag("projet futur chambre froide", DIMS));
        JSONObject event = hesitation(
                System.currentTimeMillis(),
                "notepad",
                "phantom",
                "outil inventé",
                "ajoute projet futur");
        assertTrue(DiagBehaviorIndex.indexOne(store, embedder, event));

        float[] q = bag("projet futur", DIMS);
        List<VectorStore.Hit> memHits = store.search(q, 5, 0.1f, VectorStore.NS_MEMORY);
        List<VectorStore.Hit> diagHits = store.search(q, 5, 0.1f, VectorStore.NS_DIAG);
        List<VectorStore.Hit> defaultHits = store.search(q, 5, 0.1f);

        assertEquals(1, memHits.size());
        assertEquals("mem:projects", memHits.get(0).memoryKey);
        assertEquals(1, diagHits.size());
        assertTrue(diagHits.get(0).memoryKey.startsWith("diag:"));
        assertEquals("recherche défaut = mémoire seule", 1, defaultHits.size());
        assertEquals("mem:projects", defaultHits.get(0).memoryKey);
        assertEquals(1, store.size(VectorStore.NS_MEMORY));
        assertEquals(1, store.size(VectorStore.NS_DIAG));
    }

    @Test
    public void purge_removesDiagOlderThanRetention() throws Exception {
        long oldT = System.currentTimeMillis()
                - (DiagBehaviorIndex.RETENTION_DAYS + 2) * 24L * 60L * 60L * 1000L;
        long recentT = System.currentTimeMillis() - 2L * 24L * 60L * 60L * 1000L;

        store.upsert("diag:old:notepad:hesitation:aaaa",
                bag("old hesitation notepad", DIMS),
                VectorStore.NS_DIAG,
                "{\"tool\":\"notepad\",\"kind\":\"hesitation\"}",
                oldT);
        store.upsert("diag:new:notepad:hesitation:bbbb",
                bag("recent hesitation notepad", DIMS),
                VectorStore.NS_DIAG,
                "{\"tool\":\"notepad\",\"kind\":\"hesitation\"}",
                recentT);
        store.upsert("mem:keep", bag("souvenir conversation", DIMS));

        assertEquals(2, store.size(VectorStore.NS_DIAG));
        int purged = store.purgeNamespaceOlderThan(
                VectorStore.NS_DIAG, DiagBehaviorIndex.RETENTION_DAYS);
        assertEquals(1, purged);
        assertFalse(store.hasVector("diag:old:notepad:hesitation:aaaa"));
        assertTrue(store.hasVector("diag:new:notepad:hesitation:bbbb"));
        assertTrue("mémoire non touchée par purge diag", store.hasVector("mem:keep"));
    }

    @Test
    public void synthesize_oftenAndFirstTime() {
        long t = System.currentTimeMillis() - 3L * 24L * 60L * 60L * 1000L;
        String payload = "{\"day\":\"" + LocalDate.now().minusDays(3)
                + "\",\"kind\":\"hesitation\",\"tool\":\"notepad\","
                + "\"reason\":\"phantom\",\"user_msg\":\"projet futur\"}";
        VectorStore.Hit one = new VectorStore.Hit("diag:x:notepad:hesitation:1",
                0.9f, VectorStore.NS_DIAG, payload, t);

        String rare = DiagBehaviorIndex.synthesizeSearchAnswer("ça arrive souvent ?",
                java.util.Collections.singletonList(one));
        assertTrue(rare.toLowerCase(Locale.ROOT).contains("rarement")
                || rare.toLowerCase(Locale.ROOT).contains("seule"));

        String first = DiagBehaviorIndex.synthesizeSearchAnswer("c'est la première fois ?",
                java.util.Arrays.asList(one, one, one, one));
        assertTrue(first.toLowerCase(Locale.ROOT).contains("non")
                || first.toLowerCase(Locale.ROOT).contains("pas la première"));
    }

    private static JSONObject hesitation(long t, String tool, String reason,
            String detail, String userMsg) throws Exception {
        return new JSONObject()
                .put("t", t)
                .put("type", "tool_hesitation")
                .put("tool", tool)
                .put("reason", reason)
                .put("detail", detail)
                .put("user_msg", userMsg);
    }

    /** Embedding déterministe type bag-of-words pour tests sans ONNX. */
    private static float[] bag(String text, int dims) {
        float[] v = new float[dims];
        if (text == null || text.isEmpty()) {
            v[0] = 1f;
            return VectorMath.l2Normalize(v);
        }
        String t = text.toLowerCase(Locale.ROOT)
                .replace('é', 'e').replace('è', 'e').replace('ê', 'e')
                .replace('à', 'a').replace('ù', 'u');
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= 'a' && c <= 'z') {
                v[c % dims] += 1f;
            }
        }
        for (String w : t.split("\\s+")) {
            if (w.isEmpty()) continue;
            int h = Math.abs(w.hashCode());
            v[h % dims] += 3f;
            if (w.length() > 3) {
                v[(h / 31) % dims] += 1.5f;
            }
        }
        return VectorMath.l2Normalize(v);
    }
}
