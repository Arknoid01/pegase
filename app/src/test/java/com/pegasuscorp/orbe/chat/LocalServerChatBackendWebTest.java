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

    @Test public void questionsPerso_ouExplications_pasDeRecherche() {
        assertEquals(LocalServerChatBackend.WEB_NO, n("Qui es-tu ?"));
        assertEquals(LocalServerChatBackend.WEB_NO, n("On a des explications à ça ?"));
        assertEquals(LocalServerChatBackend.WEB_NO, n("C'est quoi un trou noir ?"));
        assertEquals(LocalServerChatBackend.WEB_NO, n("tu penses quoi des jeux de gestion ?"));
        assertEquals(LocalServerChatBackend.WEB_NO, n("tu fais quoi ce soir ?"));
        assertEquals(LocalServerChatBackend.WEB_NO, n("pourquoi le ciel est bleu ?"));
        assertEquals(LocalServerChatBackend.WEB_NO, n("je devrais acheter quoi comme clavier ?"));
    }

    @Test public void faitsPrecisOuDemandes_recherche() {
        assertEquals(LocalServerChatBackend.WEB_YES, n("Qui est Fred Vasseur ?"));
        assertEquals(LocalServerChatBackend.WEB_YES, n("quand sort le prochain GTA ?"));
        assertEquals(LocalServerChatBackend.WEB_YES, n("tu sais qui a gagné hier ?"));
        assertEquals(LocalServerChatBackend.WEB_YES, n("il s'est passé quoi en Ukraine cette semaine ?"));
        assertEquals(LocalServerChatBackend.WEB_YES, n("cherche les horaires de la déchetterie"));
    }

    @Test public void jamaisDeDecisionParLeModele() {
        for (String s : new String[]{"Qui es-tu ?", "c'est quoi Godot ?", "ok et donc ?", "comment ça marche ?"})
            org.junit.Assert.assertNotEquals(LocalServerChatBackend.WEB_ASK, n(s));
    }
}
