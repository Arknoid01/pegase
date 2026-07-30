package com.pegasuscorp.orbe.tools.device;

import org.junit.Test;

import static org.junit.Assert.*;

public class MathCalcTriggerTest {

    @Test
    public void matches_signsAndDigits() {
        assertTrue(MathCalcTrigger.matches("12×4"));
        assertTrue(MathCalcTrigger.matches("100 ÷ 4"));
        assertTrue(MathCalcTrigger.matches("50+36"));
        assertTrue(MathCalcTrigger.matches("12 fois 4"));
        assertTrue(MathCalcTrigger.matches("marge 36% sur 50 euros"));
    }

    @Test
    public void rejects_datesTimesPhones() {
        assertFalse(MathCalcTrigger.matches("15/07/2026"));
        assertFalse(MathCalcTrigger.matches("21h32"));
        assertFalse(MathCalcTrigger.matches("06 12 34 56 78"));
        assertFalse(MathCalcTrigger.matches("Bonjour Pégase"));
    }

    @Test
    public void rejects_longDocumentsAndMarkdown() {
        StringBuilder longDoc = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            longDoc.append("Le navigateur a le plus de marge de progression ")
                    .append(i).append(". ");
        }
        assertFalse(MathCalcTrigger.matches(longDoc.toString()));

        assertFalse(MathCalcTrigger.matches(
                "# Pégase v2\n\n## Les 5 axes\n\n- moins de bruit\n- 20-50h de corpus"));
        assertFalse(MathCalcTrigger.matches(
                "calcule 2+2\nencore une ligne\net une troisième\net une quatrième"));
    }
}
