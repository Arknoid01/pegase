package com.pegasuscorp.orbe.tools;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class NegativeInstructionTest {

    @Test
    public void detectsForbiddenActions() {
        assertTrue(NegativeInstruction.forbidsAction("Je veux programmer un rappel. Ne crée rien "
                + "pour le moment. Présente-moi simplement les paramètres."));
        assertTrue(NegativeInstruction.forbidsAction("Ne modifie aucune mémoire pour le moment."));
        assertTrue(NegativeInstruction.forbidsAction("Montre-moi juste ce que tu enverrais"));
        assertFalse(NegativeInstruction.forbidsAction("Ajoute un rappel demain à 10 h"));
        assertFalse(NegativeInstruction.forbidsAction("N'oublie pas de me rappeler le dentiste"));
    }

    @Test
    public void onlyWriteCallsAreBlocked() throws Exception {
        assertTrue(NegativeInstruction.isWriteCall("agenda", new JSONObject().put("action", "add")));
        assertFalse(NegativeInstruction.isWriteCall("agenda", new JSONObject().put("action", "today")));
        assertFalse(NegativeInstruction.isWriteCall("weather", new JSONObject()));
    }
}
