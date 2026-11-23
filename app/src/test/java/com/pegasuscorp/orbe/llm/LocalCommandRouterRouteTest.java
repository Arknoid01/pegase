package com.pegasuscorp.orbe.llm;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Questions sur ce que Pégase sait de l'utilisateur : discussion, pas commande. */
public class LocalCommandRouterRouteTest {

    @Test public void souvenirs_ne_sont_pas_des_commandes() {
        assertTrue(LocalCommandRouter.route("Tu te souviens de ce que j'aime comme musique ?").isEmpty());
        assertTrue(LocalCommandRouter.route("Est-ce que tu sais quelle musique j'écoute ?").isEmpty());
    }

    @Test public void commandes_restent_des_commandes() {
        assertTrue(LocalCommandRouter.route("mets de la musique").contains("spotify"));
        assertTrue(LocalCommandRouter.route("mets un réveil à 7h").contains("alarm"));
    }
}
