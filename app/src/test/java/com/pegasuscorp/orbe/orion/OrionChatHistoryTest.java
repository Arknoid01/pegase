package com.pegasuscorp.orbe.orion;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OrionChatHistoryTest {

    @org.junit.Before
    public void setUp() {
        OrionChatHistory.resetForTests();
    }

    @Test
    public void keepsUserAndAssistantTurns() {
        OrionChatHistory h = OrionChatHistory.get();
        h.addUser("bonjour");
        h.beginAssistant();
        h.appendAssistant("sal");
        h.appendAssistant("ut");
        h.finishAssistant("salut");
        assertEquals(2, h.snapshot().size());
        assertTrue(h.snapshot().get(0).fromUser);
        assertFalse(h.snapshot().get(1).fromUser);
        assertEquals("salut", h.lastAssistantText());
    }
}
