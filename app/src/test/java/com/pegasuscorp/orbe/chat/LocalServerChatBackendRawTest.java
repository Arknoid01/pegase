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
        assertEquals(220, LocalServerChatBackend.maxReplyTokens("salut", false));
        assertEquals(280, LocalServerChatBackend.maxReplyTokens("quel prix", true));
    }

    @Test public void fitUserMessage_tronque_si_besoin() {
        String huge = "B".repeat(20_000);
        String out = LocalServerChatBackend.fitUserMessage(huge, 10_000);
        assertTrue(out.contains("raccourci"));
        assertTrue(out.length() < huge.length());
    }
}