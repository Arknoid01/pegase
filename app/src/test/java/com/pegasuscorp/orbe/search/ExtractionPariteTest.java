package com.pegasuscorp.orbe.search;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Parité avec le prototype PC : on rejoue en Java les collectes FIGÉES des bancs
 * (/mnt/jeux/dev/recherche/tests/figes) et on compare la réponse extraite à celle du Python
 * (tests/runs/*.jsonl). Ignoré si le dossier du prototype ou le modèle sont absents.
 */
@RunWith(RobolectricTestRunner.class)
public class ExtractionPariteTest {

    private static final File PROTO = new File("/mnt/jeux/dev/recherche/tests");
    private static MultilingualEmbedder emb;

    @BeforeClass
    public static void ouvrir() throws Exception {
        File snaps = new File("/tmp/fastembed_cache/"
                + "models--Qdrant--paraphrase-multilingual-MiniLM-L12-v2-onnx-Q/snapshots");
        File[] l = snaps.listFiles();
        if (l != null && l.length > 0 && MultilingualEmbedder.isInstalled(l[0])) {
            emb = MultilingualEmbedder.open(l[0]);
        }
    }

    @AfterClass
    public static void fermer() throws Exception {
        if (emb != null) emb.close();
    }

    static Collecte lire(JSONObject o) {
        Collecte c = new Collecte();
        c.question = o.optString("question", "");
        c.envoyee = o.optString("envoyee", c.question);
        JSONArray r = o.optJSONArray("retire");
        if (r != null) for (int i = 0; i < r.length(); i++) c.retire.add(r.optString(i));
        if (!o.isNull("proposition") && o.has("proposition")) c.proposition = o.optString("proposition");
        if (o.has("question_originale")) c.questionOriginale = o.optString("question_originale");
        JSONObject dir = o.optJSONObject("dossier");
        if (dir != null) {
            JSONArray src = dir.optJSONArray("sources");
            if (src != null && src.length() == 1 && "calcul-local".equals(src.optString(0))) {
                c.direct = Extraction.dossierCalcul(dir.optString("question"), c.retire);
            } else {
                Dossier d = new Dossier();
                d.question = dir.optString("question", c.question);
                d.refus = dir.optString("refus", null);
                c.direct = d;
            }
            return c;
        }
        JSONArray pages = o.optJSONArray("pages");
        if (pages != null) {
            for (int i = 0; i < pages.length(); i++) {
                JSONArray p = pages.optJSONArray(i);
                c.pages.add(new Collecte.Page(p.optString(0), p.optString(1), p.optString(2)));
            }
        }
        return c;
    }

    private static File dernier(String suffixe) {
        File[] l = new File(PROTO, "runs").listFiles(
                (d, n) -> n.endsWith("_" + suffixe + ".jsonl"));
        if (l == null || l.length == 0) return null;
        java.util.Arrays.sort(l);
        return l[l.length - 1];
    }

    private void verifier(String figes, String run, double minimum) throws Exception {
        assumeTrue("modèle absent", emb != null);
        File runF = dernier(run);
        assumeTrue("banc PC absent : " + run, runF != null && new File(PROTO, "figes/" + figes).isDirectory());
        int egal = 0, total = 0;
        List<String> ecarts = new ArrayList<>();
        for (String ligne : Files.readAllLines(runF.toPath(), StandardCharsets.UTF_8)) {
            JSONObject r = new JSONObject(ligne);
            File f = new File(PROTO, "figes/" + figes + "/" + r.getString("id") + ".json");
            if (!f.exists()) continue;
            Collecte c = lire(new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
            Dossier d = Assemblage.assembler(c, emb::embed);
            String javaRep = Extraction.extractive(d, Extraction.phrasesCles(d, emb::embed));
            String pyRep = r.optString("extractive", "");
            total++;
            if (javaRep.equals(pyRep)) egal++;
            else ecarts.add(r.getString("id") + "\n   PY : " + pyRep + "\n   JV : " + javaRep);
        }
        double taux = total == 0 ? 0 : (double) egal / total;
        System.out.println("[parité " + figes + "] " + egal + "/" + total);
        for (String e : ecarts) System.out.println(e);
        assertTrue("parité " + figes + " : " + egal + "/" + total + "\n" + String.join("\n", ecarts),
                taux >= minimum);
    }

    @Test
    public void pariteCorpusBrave() throws Exception {
        verifier("brave-v1", "r-brave-e4", 0.95);
    }

    @Test
    public void pariteActu() throws Exception {
        verifier("actu-brave-v1", "r-actub-e4", 0.9);
    }

    @Test
    public void pariteInedites() throws Exception {
        verifier("brave-v2-ined", "ined-brave-v2", 0.9);
    }
}
