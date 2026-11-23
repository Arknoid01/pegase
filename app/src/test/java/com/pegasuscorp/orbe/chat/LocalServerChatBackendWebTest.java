package com.pegasuscorp.orbe.chat;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Tri « recherche web » sans modèle : oui évident / non évident / à décider. */
public class LocalServerChatBackendWebTest {

    private static int n(String s) { return LocalServerChatBackend.webNeed(s); }

    @Test public void faitsFrais_rechercheDirecte() {
        assertEquals(LocalServerChatBackend.WEB_YES, n("quel est le prix du gazole en ce moment ?"));
        assertEquals(LocalServerChatBackend.WEB_YES, n("Tu as les résultats du GP de F1 d'hier ?"));
        assertEquals(LocalServerChatBackend.WEB_YES, n("qu'a dit Vasseur après l'accrochage ?"));
    }

    @Test public void affirmationsEtPetitesPhrases_pasDeRecherche() {
        assertEquals(LocalServerChatBackend.WEB_NO, n("J'aime Leclerc mais vraiment il faut arrêter"));
        assertEquals(LocalServerChatBackend.WEB_NO, n("Toujours pas de neige c'est fou ça"));
        assertEquals(LocalServerChatBackend.WEB_NO, n("salut, ça va ?"));
        assertEquals(LocalServerChatBackend.WEB_NO, n("merci"));
    }

    @Test public void questionsAmbigues_leModeleDecide() {
        assertEquals(LocalServerChatBackend.WEB_ASK, n("Qui es-tu ?"));
        assertEquals(LocalServerChatBackend.WEB_ASK, n("On a des explications à ça ?"));
        assertEquals(LocalServerChatBackend.WEB_ASK, n("C'est quoi un trou noir ?"));
    }
}
