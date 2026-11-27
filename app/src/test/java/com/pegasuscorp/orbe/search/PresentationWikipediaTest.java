package com.pegasuscorp.orbe.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PresentationWikipediaTest {

    private static Collecte collecte(String question, String titre, String texte) {
        Collecte c = new Collecte();
        c.question = question;
        c.pages.add(new Collecte.Page("https://fr.wikipedia.org/wiki/Charles_Leclerc", titre, texte));
        c.pages.add(new Collecte.Page("https://www.elle.fr/x", "Elle", "Quand il retire son casque…"));
        return c;
    }

    @Test public void quiEst_wikipediaEnTete() {
        Dossier d = new Dossier();
        d.passages.add(new Dossier.Passage("Quand il retire son casque, il devient sensible.", "elle.fr", "https://www.elle.fr/x"));
        d.reponse = "Quand il retire son casque, il devient sensible.";
        PresentationWikipediaTest.class.getName();
        MoteurRecherche.presentationWikipedia(d, collecte("qui est charles leclerc",
                "Charles Leclerc (pilote automobile)",
                "Charles Leclerc, né le 16 octobre 1997 à Monte-Carlo, est un pilote automobile monégasque. "
                        + "Il court en Formule 1 chez Ferrari depuis 2019. Il a été champion de F2."));
        assertEquals("fr.wikipedia.org", d.passages.get(0).site);
        assertTrue(d.passages.get(0).texte.endsWith("depuis 2019."));
        assertTrue(d.reponse.startsWith("Charles Leclerc, né le 16 octobre 1997"));
    }

    @Test public void abstention_leveeParWikipedia() {
        Dossier d = new Dossier();
        d.abstention = true;
        MoteurRecherche.presentationWikipedia(d, collecte("qui est charles leclerc", "Charles Leclerc",
                "Charles Leclerc est un pilote automobile monégasque. Il court chez Ferrari."));
        assertFalse(d.abstention);
        assertEquals("Charles Leclerc est un pilote automobile monégasque.", d.reponse);
    }

    @Test public void pasQuiEst_ouTitreSansRapport_rienNeChange() {
        Dossier d = new Dossier();
        MoteurRecherche.presentationWikipedia(d, collecte("quel temps fait-il à Brest", "Charles Leclerc",
                "Charles Leclerc est un pilote automobile monégasque. Il court chez Ferrari."));
        assertTrue(d.passages.isEmpty());
        Dossier e = new Dossier();
        MoteurRecherche.presentationWikipedia(e, collecte("qui est Fred Vasseur", "Charles Leclerc",
                "Charles Leclerc est un pilote automobile monégasque. Il court chez Ferrari."));
        assertTrue(e.passages.isEmpty());
    }
}
