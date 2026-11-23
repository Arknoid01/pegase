package com.pegasuscorp.orbe.search;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parité avec le prototype PC (/mnt/jeux/dev/recherche) : mêmes jetons que la bibliothèque
 * Python « tokenizers » et mêmes vecteurs que fastembed, sur 81 phrases réelles des bancs.
 * Le modèle (250 Mo) n'est pas dans le dépôt : test ignoré si le cache fastembed du PC
 * est absent (variable ORBE_MINILM_DIR pour un autre dossier).
 */
@RunWith(RobolectricTestRunner.class)
public class UnigramTokenizerTest {

    private static File modelDir() {
        String env = System.getenv("ORBE_MINILM_DIR");
        if (env != null && !env.isEmpty()) return new File(env);
        File snaps = new File("/tmp/fastembed_cache/"
                + "models--Qdrant--paraphrase-multilingual-MiniLM-L12-v2-onnx-Q/snapshots");
        File[] l = snaps.listFiles();
        return l != null && l.length > 0 ? l[0] : snaps;
    }

    private static JSONArray reference() throws Exception {
        try (InputStream in = UnigramTokenizerTest.class.getClassLoader()
                .getResourceAsStream("search/parite_embeddings.json")) {
            return new JSONArray(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    public void memesJetonsQuePython() throws Exception {
        File dir = modelDir();
        assumeTrue("tokenizer.json absent", new File(dir, "tokenizer.json").exists());
        UnigramTokenizer tok;
        try (InputStream in = new java.io.FileInputStream(new File(dir, "tokenizer.json"))) {
            tok = UnigramTokenizer.fromTokenizerJson(in);
        }
        JSONArray ref = reference();
        int ok = 0;
        List<String> ko = new ArrayList<>();
        for (int i = 0; i < ref.length(); i++) {
            JSONObject o = ref.getJSONObject(i);
            JSONArray ids = o.getJSONArray("ids");
            long[] want = new long[ids.length()];
            for (int k = 0; k < want.length; k++) want[k] = ids.getLong(k);
            long[] got = tok.encode(o.getString("texte"));
            if (java.util.Arrays.equals(want, got)) ok++;
            else ko.add(o.getString("texte").substring(0, Math.min(60, o.getString("texte").length())));
        }
        assertTrue("jetons différents (" + ok + "/" + ref.length() + ") : " + ko, ko.isEmpty());
    }

    @Test
    public void memesVecteursQueFastembed() throws Exception {
        File dir = modelDir();
        assumeTrue("modèle absent", MultilingualEmbedder.isInstalled(dir));
        JSONArray ref = reference();
        List<String> textes = new ArrayList<>();
        for (int i = 0; i < ref.length(); i++) {
            String t = ref.getJSONObject(i).getString("texte");
            textes.add(t.isEmpty() ? " " : t);
        }
        float[][] got;
        try (MultilingualEmbedder e = MultilingualEmbedder.open(dir)) {
            got = e.embed(textes);
        }
        double pire = 1.0;
        for (int i = 0; i < ref.length(); i++) {
            JSONArray v = ref.getJSONObject(i).getJSONArray("vec");
            double dot = 0;
            for (int k = 0; k < v.length(); k++) dot += v.getDouble(k) * got[i][k];
            pire = Math.min(pire, dot);
        }
        assertTrue("cosinus minimal Java/Python = " + pire, pire > 0.999);
    }

    @Test
    public void cacheBinaireIdentique() throws Exception {
        File dir = modelDir();
        assumeTrue("tokenizer.json absent", new File(dir, "tokenizer.json").exists());
        File copie = File.createTempFile("tok", ".json");
        java.nio.file.Files.copy(new File(dir, "tokenizer.json").toPath(), copie.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        UnigramTokenizer a = UnigramTokenizer.loadCached(copie);   // écrit le cache
        UnigramTokenizer b = UnigramTokenizer.loadCached(copie);   // relit le cache
        String s = "Le Néerlandais Max Verstappen a gagné le Grand Prix de Las Vegas.";
        assertArrayEquals(a.encode(s), b.encode(s));
        //noinspection ResultOfMethodCallIgnored
        new File(copie.getPath() + ".bin").delete();
        //noinspection ResultOfMethodCallIgnored
        copie.delete();
    }
}
