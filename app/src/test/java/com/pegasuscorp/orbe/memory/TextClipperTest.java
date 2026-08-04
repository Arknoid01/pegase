package com.pegasuscorp.orbe.memory;

import org.junit.Test;

import static org.junit.Assert.*;

public class TextClipperTest {

    @Test
    public void shortText_unchanged() {
        assertEquals("Décision : on garde A.",
                TextClipper.clipAtSentence("Décision : on garde A.", 160, 320));
    }

    @Test
    public void clipsAtSentenceBoundary_whenOverHardMax() {
        StringBuilder sb = new StringBuilder();
        sb.append("On a choisi l'approche B pour la mémoire. ");
        sb.append("Le contexte est que Groq refuse les prompts trop longs. ");
        while (sb.length() < 400) {
            sb.append("Encore du contexte secondaire pour forcer la coupe. ");
        }
        String longDecision = sb.toString();
        String clipped = TextClipper.clipAtSentence(longDecision, 160, 320);
        assertTrue(clipped.length() <= 321);
        assertTrue(clipped.length() < longDecision.length());
        assertTrue(clipped.contains("approche B"));
        assertTrue(clipped.endsWith(".") || clipped.endsWith("…"));
    }

    @Test
    public void underHardMax_keepsFullSouvenir() {
        String decision = "Décision : on garde B. Contexte : Groq limite le TPM.";
        assertEquals(decision, TextClipper.clipAtSentence(decision, 160, 320));
    }

    @Test
    public void hardMax_respectedWithoutMidWordWhenPossible() {
        String s = "MotA MotB MotC MotD MotE MotF MotG MotH MotI MotJ MotK MotL MotM MotN MotO";
        String clipped = TextClipper.clipAtSentence(s, 20, 40);
        assertTrue(clipped.length() <= 41);
        assertFalse(clipped.matches(".*Mot[A-Z]$")); // pas un mot coupé sans …
    }
}
