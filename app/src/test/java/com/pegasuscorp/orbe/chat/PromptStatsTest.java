package com.pegasuscorp.orbe.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class PromptStatsTest {

    @Before @After
    public void reset() {
        PromptStats.resetForTest();
    }

    @Test
    public void sansRequete_pasDeDonnee() {
        assertFalse(PromptStats.hasData());
        assertEquals(0, PromptStats.percent());
        assertFalse(PromptStats.compactedRecently(1000));
    }

    @Test
    public void pourcentage_parRapportAuBudgetDuNiveau() {
        // Groq (TIGHT) : 6 000 tokens x 4 = 24 000 caractères
        PromptStats.note(12_000, PromptBudget.Level.TIGHT);
        assertTrue(PromptStats.hasData());
        assertEquals(50, PromptStats.percent());
        // autres fournisseurs (NORMAL) : 80 000 caractères
        PromptStats.note(20_000, PromptBudget.Level.NORMAL);
        assertEquals(25, PromptStats.percent());
    }

    @Test
    public void compaction_recenteEtFormat() {
        PromptStats.noteCompaction();
        assertTrue(PromptStats.compactedRecently(60_000));
        assertEquals("Contexte 42 %", PromptStats.formatGauge(42, false));
        assertEquals("Contexte 91 % · anciens résultats abrégés", PromptStats.formatGauge(91, true));
    }
}
