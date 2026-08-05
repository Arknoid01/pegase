package com.pegasuscorp.orbe.copilot;

import org.junit.Test;

import static org.junit.Assert.*;

public class ScreenPiiRedactorTest {

    @Test
    public void redactsEmailAndPhone() {
        String raw = "Contact : jean.dupont@example.com / 0612345678";
        String out = ScreenPiiRedactor.redact(raw);
        assertTrue(out.contains("[email]"));
        assertTrue(out.contains("[tel]"));
        assertFalse(out.contains("example.com"));
    }

    @Test
    public void redactsOtpAndCard() {
        String raw = "Code 482913 carte 4111111111111111";
        String out = ScreenPiiRedactor.redact(raw);
        assertTrue("otp in: " + out, out.contains("[otp]"));
        assertTrue("carte in: " + out, out.contains("[carte]"));
    }

    @Test
    public void redactsFrenchMobile() {
        String out = ScreenPiiRedactor.redact("Appelle le 06 12 34 56 78");
        assertTrue(out.contains("[tel]"));
    }

    @Test
    public void leavesNormalUiLabels() {
        String raw = "Rechercher  Astronomie et espace  Envoyer";
        assertEquals(raw, ScreenPiiRedactor.redact(raw));
    }
}
