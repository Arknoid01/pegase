package com.pegasuscorp.orbe.session;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TurnCancelPolicyTest {

    @Test
    public void phrasesCourtes_annulent() {
        assertTrue(TurnCancelPolicy.isCancel("Stop"));
        assertTrue(TurnCancelPolicy.isCancel("  Annule tout ! "));
        assertTrue(TurnCancelPolicy.isCancel("Laisse tomber."));
        assertTrue(TurnCancelPolicy.isCancel("Non, attends"));
        assertTrue(TurnCancelPolicy.isCancel("ARRÊTE"));
        assertTrue(TurnCancelPolicy.isCancel("ça suffit"));
    }

    @Test
    public void demandesRealles_nAnnulentPas() {
        assertFalse(TurnCancelPolicy.isCancel("arrête la musique"));
        assertFalse(TurnCancelPolicy.isCancel("annule mon rendez-vous de demain"));
        assertFalse(TurnCancelPolicy.isCancel("stop the press et cherche la météo"));
        assertFalse(TurnCancelPolicy.isCancel(""));
        assertFalse(TurnCancelPolicy.isCancel(null));
    }
}
