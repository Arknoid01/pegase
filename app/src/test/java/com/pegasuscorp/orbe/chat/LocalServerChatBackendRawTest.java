package com.pegasuscorp.orbe.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LocalServerChatBackendRawTest {

    @Test public void clipRaw_inchange_si_court() {
        assertEquals("spam", LocalServerChatBackend.clipRaw("spam"));
    }

    @Test public void clipRaw_tronque_avec_marqueur() {
        String huge = "A".repeat(LocalServerChatBackend.RAW_MAX_CHARS + 100);
        String out = LocalServerChatBackend.clipRaw(huge);
        assertTrue(out.contains("\n[…]\n"));
        assertTrue(out.length() < huge.length());
        assertTrue(out.startsWith("A"));
    }

    @Test public void maxReplyTokens_explique_plus_long() {
        assertEquals(520, LocalServerChatBackend.maxReplyTokens("explique-moi la relativité", false));
        assertEquals(520, LocalServerChatBackend.maxReplyTokens("résume ce passage", false));
        assertEquals(640, LocalServerChatBackend.maxReplyTokens(
                "lis\n\n---\nDocument joint « x » :\n\nhello", false));
        assertEquals(220, LocalServerChatBackend.maxReplyTokens("salut", false));
        assertEquals(280, LocalServerChatBackend.maxReplyTokens("quel prix", true));
    }

    @Test public void emotion_detecte_partage_et_tokens() {
        String noel = "Normalement c'est mon dernier Noël en tant qu'artisan chef boucher";
        assertTrue(LocalServerChatBackend.looksEmotionalTurn(noel));
        assertEquals(420, LocalServerChatBackend.maxReplyTokens(noel, false));
        assertTrue(LocalServerChatBackend.looksEmotionalTurn(
                "Si jamais j'ai fait les commandes de Noël"));
        assertTrue(!LocalServerChatBackend.looksEmotionalTurn("bonjour"));
    }

    @Test public void fitUserMessage_tronque_si_besoin() {
        String huge = "B".repeat(20_000);
        String out = LocalServerChatBackend.fitUserMessage(huge, 10_000);
        assertTrue(out.contains("raccourci"));
        assertTrue(out.length() < huge.length());
    }
}