package com.pegasuscorp.orbe.copilot.apps;

import org.junit.Test;

import static org.junit.Assert.*;

public class CursorMicActionTest {

    @Test
    public void looksLikeMicRequest_commonAliases() {
        assertTrue(CursorMicAction.looksLikeMicRequest("micro"));
        assertTrue(CursorMicAction.looksLikeMicRequest("Micro"));
        assertTrue(CursorMicAction.looksLikeMicRequest("microphone"));
        assertTrue(CursorMicAction.looksLikeMicRequest("mic"));
        assertTrue(CursorMicAction.looksLikeMicRequest("[icône: micro]"));
        assertTrue(CursorMicAction.looksLikeMicRequest("bouton micro"));
        assertTrue(CursorMicAction.looksLikeMicRequest("Démarrer la saisie vocale"));
        assertTrue(CursorMicAction.looksLikeMicRequest("Start voice input"));
        assertTrue(CursorMicAction.looksLikeMicRequest("clique sur le micro"));
        assertTrue(CursorMicAction.looksLikeMicRequest("de micro"));
        assertTrue(CursorMicAction.looksLikeMicRequest("du micro"));
        assertTrue(CursorMicAction.looksLikeMicRequest("icone de micro"));
    }

    @Test
    public void looksLikeMicRequest_rejectsUnrelated() {
        assertFalse(CursorMicAction.looksLikeMicRequest(""));
        assertFalse(CursorMicAction.looksLikeMicRequest(null));
        assertFalse(CursorMicAction.looksLikeMicRequest("Nouvel agent"));
        assertFalse(CursorMicAction.looksLikeMicRequest("Recherche"));
        assertFalse(CursorMicAction.looksLikeMicRequest("envoyer"));
    }

    @Test
    public void micLabels_includeObservedFrenchA11y() {
        boolean found = false;
        for (String label : CursorMicAction.MIC_LABELS) {
            if ("Démarrer la saisie vocale".equals(label)) {
                found = true;
                break;
            }
        }
        assertTrue(found);
    }
}
