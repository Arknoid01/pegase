package com.pegasuscorp.orbe.copilot;

import org.junit.Test;

import static org.junit.Assert.*;

public class OpenClaimGuardTest {

    @Test
    public void detectsAlreadyOpenClaims() {
        assertTrue(OpenClaimGuard.claimsAlreadyOpen(
                "L'application tourne encore en arrière-plan."));
        assertTrue(OpenClaimGuard.claimsAlreadyOpen("Cursor est déjà ouvert."));
        assertTrue(OpenClaimGuard.claimsAlreadyOpen("Pas besoin de l'ouvrir, c'est lancé."));
        assertFalse(OpenClaimGuard.claimsAlreadyOpen("J'ouvre Cursor."));
    }

    @Test
    public void replaceUsesFallback() {
        assertEquals("J'ouvre Cursor.",
                OpenClaimGuard.replaceAlreadyOpenClaim(
                        "Ça tourne déjà.", "J'ouvre Cursor."));
    }
}
