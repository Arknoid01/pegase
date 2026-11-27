package com.pegasuscorp.orbe.chatbot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Demandes de travail (journal du 2026-11-26) : pas de banque de conversation, pas de question finale. */
public class ChatbotTacheTest {

    private static boolean task(String msg) {
        return ChatbotEngine.isTask(Normalizer.normalize(msg).key);
    }

    @Test
    public void demandesDeTravailReconnues() {
        assertTrue(task("« J'avais prévu de travailler sur Fableris cet après-midi, mais finalement je préfère "
                + "avancer sur Pégase. Je n'ai qu'une heure devant moi. Qu'est-ce que tu me conseilles de faire ? »"));
        assertTrue(task("J'ai 45 minutes devant moi. Je voudrais améliorer Pégase. Propose-moi trois choses concrètes."));
        assertTrue(task("Donne-moi exactement trois idées pour améliorer Pégase. Pour chaque idée, indique un exemple."));
        assertTrue(task("explique moi comment marche le cache"));
        assertTrue(task("donne moi 5 idées de cadeaux"));
    }

    @Test
    public void conversationNonTouchee() {
        assertFalse(task("je suis vraiment crevé ce soir x)"));
        assertFalse(task("ca y est je crois c'est vraiment mon dernier noel a la boutique"));
        assertFalse(task("les commandes de noel sont faites, j'espere que j'ai pas trop commandé"));
        assertFalse(task("tu fais quoi ?"));
    }

    @Test
    public void questionFinaleRetireeSiInterdite() {
        assertTrue(ChatbotVoice.forbidsQuestions("Ne me pose aucune question et ne parle pas de ça."));
        assertTrue(ChatbotVoice.forbidsQuestions("réponds sans poser de question"));
        assertFalse(ChatbotVoice.forbidsQuestions("pose-moi des questions"));
        assertEquals("1. A.\n2. B. 3. Exemple : C.",
                ChatbotVoice.stripTrailingQuestions("1. A.\n2. B. 3. Exemple : C. Qu'en penses-tu ?"));
        assertEquals("Tu veux quoi ?", ChatbotVoice.stripTrailingQuestions("Tu veux quoi ?"));
    }

    @Test
    public void salutationRetiree() {
        assertEquals("Pour Pégase, trois pistes.", ChatbotVoice.stripGreeting("Salut Yannick ! Pour Pégase, trois pistes."));
        assertEquals("Super idée.", ChatbotVoice.stripGreeting("Super idée."));
    }
}
