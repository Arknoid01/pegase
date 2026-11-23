package com.pegasuscorp.orbe.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Étape C : rien de personnel ne doit partir vers un moteur de recherche. */
public class ConfidentialiteTest {

    private static final List<String> TERMES = Arrays.asList("Amiot", "Sylou", "Élodie", "Yannick");

    private static String net(String q) {
        return Confidentialite.nettoyer(q, TERMES, new ArrayList<>());
    }

    @Test
    public void retireEmailTelephoneIbanAdresseNumero() {
        String[][] cas = {
                {"envoie le devis à jean.dupont@exemple.fr pour la réunion de lundi", "@"},
                {"à qui est le numéro 06 12 34 56 78 qui m'appelle", "56 78"},
                {"à qui est le +33 6 12 34 56 78 qui m'appelle", "56 78"},
                {"banque de l'IBAN FR76 3000 6000 0112 3456 7890 189 virement", "3000"},
                {"horaires de la pharmacie près du 12 rue des Lilas à Nantes", "Lilas"},
                {"suivi du colis 123456789012 en retard", "123456789012"},
        };
        for (String[] c : cas) {
            String out = net(c[0]);
            assertNotNull(c[0], out);
            assertFalse(c[0] + " → " + out, out.contains(c[1]));
        }
        // la ville reste (utile, pas sensible seule)
        assertTrue(net("horaires de la pharmacie près du 12 rue des Lilas à Nantes").contains("Nantes"));
    }

    @Test
    public void retireTermesPrivesMajusculesEtAccents() {
        String out = net("horaires de la boucherie AMIOT le dimanche");
        assertFalse(out, TexteFr.fold(out).contains("amiot"));
        out = net("idée cadeau anniversaire pour elodie qui aime le jardinage");
        assertFalse(out, TexteFr.fold(out).contains("elodie"));
        assertTrue(out, out.contains("jardinage"));
    }

    @Test
    public void refuseQuandIlNeResteRien() {
        assertNull(net("appelle Sylou au 06 12 34 56 78"));
        assertNull(net("Yannick Amiot"));
    }

    @Test
    public void questionNormaleIntacte() {
        String q = "qui a gagné le Grand Prix de Las Vegas ?";
        assertEquals(q, net(q));
        assertEquals("quelle est la hauteur de la tour Eiffel ?", net("quelle est la hauteur de la tour Eiffel ?"));
    }

    @Test
    public void aucuneFuiteSurUnJeuDeQuestions() {
        String[] qs = {
                "Sylou m'a dit que la boucherie Amiot fermait, c'est vrai pour les horaires de Noël ?",
                "quel temps à Valserhône demain pour Élodie et moi",
                "Yannick cherche un restaurant italien ouvert ce soir",
                "rappelle-moi le numéro 0612345678 et l'adresse 3 avenue de la Gare",
        };
        for (String q : qs) {
            List<String> retire = new ArrayList<>();
            String out = Confidentialite.nettoyer(q, TERMES, retire);
            if (out == null) continue;
            String f = TexteFr.fold(out);
            for (String t : TERMES) assertFalse(q + " → " + out, f.contains(TexteFr.fold(t)));
            assertFalse(q + " → " + out, out.matches(".*\\d{6,}.*"));
            assertFalse(retire.isEmpty());
        }
    }

    @Test
    public void decoupageDesTermesSaisis() {
        assertEquals(Arrays.asList("Amiot", "Sylou", "Valserhône"),
                ReglagesRecherche.decouper("Amiot, Sylou\nValserhône ; x"));
    }
}
