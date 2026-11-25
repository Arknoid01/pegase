package com.pegasuscorp.orbe.chatbot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

@RunWith(RobolectricTestRunner.class)
public class ChatbotUnitTest {

    @Test
    public void normalise_les_fautes_et_le_rire() {
        Normalizer.Result r = Normalizer.normalize("Coucou, on n'a bien avancé x)");
        assertTrue(r.laughs);
        assertEquals("coucou, on a bien avancé", r.text);
        assertEquals("coucou on a bien avance", r.key);
        // Vraie négation conservée.
        assertEquals("on n'a pas fini", Normalizer.normalize("on n'a pas fini").text);
        assertEquals("tu es la ?", Normalizer.normalize("tues la ?").text);
    }

    @Test
    public void remplit_les_slots_ou_ecarte_la_variante() {
        Map<String, String> s = new HashMap<>();
        s.put("prenom", "Ana");
        assertEquals("Coucou Ana !", ChatbotEngine.fill("Coucou {prenom} !", s));
        assertNull(ChatbotEngine.fill("{inconnu}", s));
    }

    @Test
    public void garde_fou_nombres_et_noms_propres() {
        String idee = "Il en reste 9, comme à La Plagne.";
        assertTrue(ChatbotVoice.lostEssentials(idee, "Il en reste neuf !"));
        assertTrue(ChatbotVoice.lostEssentials(idee, "Il en reste 9."));
        assertFalse(ChatbotVoice.lostEssentials(idee, "9 restent, comme à la plagne !"));
        // Début de phrase : pas un nom propre.
        assertFalse(ChatbotVoice.lostEssentials("Bravo pour ça.", "Super !"));
    }

    @Test
    public void detecte_les_faits_ajoutes() {
        String fourni = "rex a encore fait des siennes\nRex ! Ton chien, il va bien ?";
        assertFalse(ChatbotVoice.addedEssentials("Ah, Rex a encore frappé ? Raconte !", fourni));
        assertTrue(ChatbotVoice.addedEssentials("Rex et Tornade ont encore fait des leurs ?", fourni));
        assertTrue(ChatbotVoice.addedEssentials("Ça fait 3 fois cette semaine, non ?", fourni));
    }

    @Test
    public void nettoie_emojis_et_ponctuation() {
        assertEquals("Génial 😂 ! On y va ?", ChatbotVoice.cleanEmojis("Génial 😂🚀! On y va?"));
        assertEquals("Rendez-vous à 18:30, voir https://x.fr", ChatbotVoice.cleanEmojis("Rendez-vous à 18:30, voir https://x.fr"));
    }

    @Test
    public void regles_et_banque_avec_encodeur_factice() throws Exception {
        JSONObject rules = new JSONObject("{\"intents\":[{\"id\":\"salutation\",\"direct\":true,"
                + "\"patterns\":[\"^(coucou|salut)$\"],\"responses\":[\"Coucou {prenom} ! x)\"]},"
                + "{\"id\":\"enigme_tous_sauf\",\"direct\":true,\"patterns\":[\"\\\\btous sauf (?P<n>\\\\d+|neuf)\\\\b\"],"
                + "\"responses\":[\"Il en reste {n}.\"]}]}");
        JSONObject bank = new JSONObject("{\"entries\":[{\"id\":\"ski\",\"theme\":\"loisirs\",\"geste\":\"relancer\","
                + "\"direct\":false,\"questions\":[\"je pars au ski\"],\"responses\":[\"Le ski, top !\"]}]}");
        Fiches f = new Fiches(new JSONObject("{\"moi\":{\"prenom\":\"Ana\"},\"entites\":[]}"));
        // Encodeur factice : « ski » dans le texte → même direction que la banque.
        ChatbotEngine e = new ChatbotEngine(rules, bank, f, texts -> {
            float[][] v = new float[texts.size()][];
            for (int i = 0; i < v.length; i++) {
                v[i] = texts.get(i).contains("ski") ? new float[]{1f, 0f} : new float[]{0f, 1f};
            }
            return v;
        }, true, new Random(1));
        e.loadVectors(null);
        ChatbotEngine.Decision d = e.decide("coucou");
        assertEquals("forte", d.zone);
        assertEquals("Coucou Ana ! 😂", d.reply);
        assertEquals("Il en reste 9.", e.decide("ils s'enfuient tous sauf neuf").reply);
        d = e.decide("demain je vais au ski avec les potes");
        assertEquals("moyenne", d.zone);
        assertEquals("banque:ski", d.source);
        assertEquals("Le ski, top !", d.ideas.get(0));
        assertEquals("faible", e.decide("quelle heure est-il").zone);
    }
}
