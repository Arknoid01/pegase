package com.pegasuscorp.orbe.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.pegasuscorp.orbe.chatbot.Normalizer;

import org.junit.Test;

/** Journal 2026-11-26 : décisions Fableris inventées, gabarit « [résumer…] » recopié. */
public class LocalServerChatBackendPastTest {

    private static boolean past(String s) {
        return LocalServerChatBackend.ASKS_PAST.matcher(
                Normalizer.stripAccents(s.toLowerCase(java.util.Locale.FRENCH).replace('’', '\''))).find();
    }

    @Test
    public void detectsQuestionsAboutSharedPast() {
        assertTrue(past("On reprend notre projet dont on avait parlé il y a quelque temps. Tu peux me "
                + "rappeler où nous en étions ?"));
        assertTrue(past("Reprenons Fableris. Je voudrais retrouver précisément notre dernière décision."));
        assertTrue(past("Peux-tu vérifier ce que tu as dans tes souvenirs ?"));
        assertFalse(past("Résume notre idée en quelques points"));
        assertFalse(past("Tu peux me rappeler de payer l'eau demain ?"));
        assertFalse(past("Salut, ça va ?"));
    }

    @Test
    public void stripsTemplateHoles() {
        assertEquals("Salut ! On reprend là où on s'était arrêtés, c'est parti !",
                LocalServerChatBackend.stripPlaceholders("Salut ! On avait décidé de [résumer brièvement "
                        + "l'étape précédente]. On était en train de préparer [mentionner la prochaine étape]. "
                        + "On reprend là où on s'était arrêtés, c'est parti !"));
        assertEquals("", LocalServerChatBackend.stripPlaceholders("On avait décidé de [résumer]."));
        assertEquals("Rien à retirer.", LocalServerChatBackend.stripPlaceholders("Rien à retirer."));
    }
}
