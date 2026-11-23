package com.pegasuscorp.orbe.llm;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

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
}
