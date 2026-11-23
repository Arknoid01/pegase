package com.pegasuscorp.orbe.chat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.pegasuscorp.orbe.tools.ToolResult;

import org.junit.Test;

/** Le résultat de recherche donné au modèle local garde les sources, pas les consignes cloud. */
public class LocalServerChatBackendSearchTest {

    @Test public void garde_reponse_et_sources_sans_consignes() {
        String ctx = "[Recherche web privée — croise les sources, cite le site, n'invente rien]\n"
                + "Question : qu'a dit Vasseur ?\n"
                + "Réponse extraite : Vasseur a jugé la collision inacceptable.\n\n"
                + "Source 1 — f1i.fr : Fred Vasseur a déclaré que c'était inacceptable entre coéquipiers.\n"
                + "Source 2 — lequipe.fr : Leclerc a écopé de dix secondes de pénalité.\n"
                + "[moteur : recherche privée (Brave)]";
        String out = LocalServerChatBackend.compactSearch(ToolResult.text("Vasseur a jugé…", ctx));
        assertTrue(out.contains("Réponse extraite : Vasseur"));
        assertTrue(out.contains("Source 2 — lequipe.fr"));
        assertFalse(out.contains("croise les sources"));
        assertFalse(out.contains("Question :"));
        assertFalse(out.contains("moteur :"));
    }

    @Test public void sans_contexte_prend_le_texte() {
        assertTrue(LocalServerChatBackend.compactSearch(ToolResult.text("2,126 €/L")).contains("2,126"));
    }
}
