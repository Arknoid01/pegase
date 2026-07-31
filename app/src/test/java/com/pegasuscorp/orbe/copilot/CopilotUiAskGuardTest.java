package com.pegasuscorp.orbe.copilot;

import org.junit.Test;

import static org.junit.Assert.*;

public class CopilotUiAskGuardTest {

    @Test
    public void asksForTechnicalViewId_detectsFrenchPhrases() {
        assertTrue(CopilotUiAskGuard.asksForTechnicalViewId(
                "Peux-tu me donner l'identifiant de la vue qui contient le texte ?"));
        assertTrue(CopilotUiAskGuard.asksForTechnicalViewId(
                "Il me faut le view_id de l'élément."));
        assertTrue(CopilotUiAskGuard.asksForTechnicalViewId("android:id/text1"));
        assertFalse(CopilotUiAskGuard.asksForTechnicalViewId("Clic envoyé sur Astronomie."));
    }

    @Test
    public void inferUiTarget_fromClickCommand() {
        assertEquals("Astronomie et espace",
                CopilotUiAskGuard.inferUiTarget("Clique sur Astronomie et espace"));
        assertEquals("Astronomie",
                CopilotUiAskGuard.inferUiTarget("cliquer sur Astronomie"));
        assertEquals("", CopilotUiAskGuard.inferUiTarget("Quelle heure est-il ?"));
    }
}
