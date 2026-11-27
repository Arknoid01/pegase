package com.pegasuscorp.orbe.llm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.List;

/**
 * Sorties réelles de LiteRT-LM (contrainte regex) qui contenaient des valeurs inventées :
 * le contrôle après analyse doit les retirer.
 */
@RunWith(RobolectricTestRunner.class)
public class LocalCommandRouterGuardTest {

    private static final List<String> TOOLS =
            Arrays.asList("alarm", "timer", "agenda", "weather", "spotify", "memory", "notepad", "sms", "call");

    @Test public void sms_invente_contact_et_date_retires() throws Exception {
        Object[] p = LocalCommandRouter.parseCompact(
                "sms to=\"contact\" message=\"Nous sommes le lundi 23 novembre 2026, 23h39.\"", TOOLS);
        JSONObject a = (JSONObject) p[1];
        LocalCommandRouter.dropCopiedValues((String) p[0], a, "envoie un message");
        assertFalse(a.has("to"));
        assertFalse(a.has("message"));
    }

    @Test public void alarme_sans_heure_dans_la_phrase_ne_garde_pas_l_heure_inventee() throws Exception {
        Object[] p = LocalCommandRouter.parseCompact("alarm add hour=\"7\" minute=\"15\" days=\"1,2,3,4,5\"", TOOLS);
        JSONObject a = (JSONObject) p[1];
        LocalCommandRouter.dropCopiedValues((String) p[0], a, "mets un réveil");
        assertFalse(a.has("hour"));
        assertFalse(a.has("minute"));
    }

    @Test public void heure_presente_dans_la_phrase_est_gardee() throws Exception {
        Object[] p = LocalCommandRouter.parseCompact("alarm add hour=\"6\" minute=\"30\"", TOOLS);
        JSONObject a = (JSONObject) p[1];
        LocalCommandRouter.dropCopiedValues((String) p[0], a, "faut que je sois debout tôt demain, genre 6h30");
        assertEquals(6, a.getInt("hour"));
        assertEquals(30, a.getInt("minute"));
    }

    /** Journal 2026-11-26 : « combien de batterie… quelles applications consomment » → Jauge. */
    @Test public void batterie_du_telephone_va_a_jauge() throws Exception {
        LocalCommandRouter.Decision d = LocalCommandRouter.quickParse("Peux-tu me dire exactement "
                + "combien de batterie il reste sur mon téléphone et quelles applications consomment le plus ?");
        assertTrue(d != null && d.toolIds.size() == 1 && d.toolIds.get(0).equals("batterie"));
        assertEquals("etat_conso", d.params.get(0).getString("action"));
        assertEquals("etat", LocalCommandRouter.quickParse("il me reste combien de batterie ?")
                .params.get(0).getString("action"));
        assertTrue(LocalCommandRouter.quickParse("Je voudrais un ordinateur portable avec une bonne batterie") == null
                || !LocalCommandRouter.quickParse("Je voudrais un ordinateur portable avec une bonne batterie")
                        .toolIds.contains("batterie"));
    }

    /** Journal 2026-11-26 15:06 : question de raisonnement qui mentionne la batterie → pas l'outil. */
    @Test public void raisonnement_sur_la_batterie_va_au_modele() {
        assertNull(LocalCommandRouter.quickParse("Mon téléphone a redémarré deux fois aujourd'hui. Depuis, sa "
                + "batterie semble descendre plus rapidement. Est-ce forcément lié ? Donne-moi trois explications "
                + "possibles, distingue les faits des hypothèses et indique comment vérifier chacune."));
        // « pourquoi ma batterie se vide » reste pour Jauge (les applis qui la vident sont la réponse)
        assertEquals("batterie", LocalCommandRouter.quickParse("pourquoi ma batterie se vide aussi vite ?")
                .toolIds.get(0));
    }
}
