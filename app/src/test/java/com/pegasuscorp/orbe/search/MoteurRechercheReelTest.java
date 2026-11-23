package com.pegasuscorp.orbe.search;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Essai RÉEL de bout en bout (réseau + clé Brave du PC, 1 crédit par question).
 * Lancé seulement si ORBE_RECHERCHE_REELLE=1 (jamais dans la suite normale).
 */
@RunWith(RobolectricTestRunner.class)
public class MoteurRechercheReelTest {

    @Test
    public void troisQuestions() throws Exception {
        assumeTrue("ORBE_RECHERCHE_REELLE=1 pour lancer", "1".equals(System.getenv("ORBE_RECHERCHE_REELLE")));
        File cle = new File(System.getProperty("user.home"), ".config/brave-search-apikey");
        File snaps = new File("/tmp/fastembed_cache/"
                + "models--Qdrant--paraphrase-multilingual-MiniLM-L12-v2-onnx-Q/snapshots");
        File[] l = snaps.listFiles();
        assumeTrue(cle.exists() && l != null && l.length > 0);
        MoteurRecherche.Reglages r = new MoteurRecherche.Reglages();
        r.cleBrave = new String(Files.readAllBytes(cle.toPath()), StandardCharsets.UTF_8)
                .replace("﻿", "").trim();
        try (MultilingualEmbedder e = MultilingualEmbedder.open(l[0])) {
            MoteurRecherche m = new MoteurRecherche(r, e::embed);
            for (String q : List.of("qui a gagné le Grand Prix de Las Vegas ?",
                    "combien de temps peut-on vivre sans manger ?",
                    "qui a inventé le téléphone, Thomas Edison ?")) {
                long t = System.currentTimeMillis();
                Dossier d = m.chercher(q);
                System.out.println("Q: " + q + "  [" + (System.currentTimeMillis() - t) + " ms, web="
                        + d.sourceWeb + ", pages=" + d.pagesLues + ", passages=" + d.passages.size()
                        + ", sites=" + d.sources + "]");
                System.out.println("  → " + d.reponse);
                assertFalse(q, d.passages.isEmpty());
                assertTrue(q, d.sourceWeb.equals("brave"));
            }
        }
    }
}
