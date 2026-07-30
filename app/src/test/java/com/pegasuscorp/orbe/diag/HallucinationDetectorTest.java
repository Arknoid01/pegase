package com.pegasuscorp.orbe.diag;

import com.pegasuscorp.orbe.memory.ContextSnapshot;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class HallucinationDetectorTest {

    @Before
    public void setUp() {
        ReasoningStore.clear();
    }

    @Test
    public void detectsPastReferenceWithoutSource() {
        assertTrue(HallucinationDetector.isPotentialHallucination(
                "On avait essayé Siffle mais il manquait de personnalité.", 0));
        assertTrue(HallucinationDetector.isPotentialHallucination(
                "La dernière fois ça plantait.", 0, false));
        assertNotNull(HallucinationDetector.reason(
                "Tu m'avais dit de garder le brief.", 0, false));
    }

    @Test
    public void ignoresWhenChunksOrReliableTool() {
        assertFalse(HallucinationDetector.isPotentialHallucination(
                "On avait essayé le sync réseau.", 2));
        assertFalse(HallucinationDetector.isPotentialHallucination(
                "On avait essayé le sync réseau.", 0, true));
    }

    @Test
    public void ignoresFactualWithoutPast() {
        assertFalse(HallucinationDetector.isPotentialHallucination(
                "Je propose d'utiliser requestAnimationFrame.", 0));
    }

    @Test
    public void reasoningCard_marksHallucination() {
        ReasoningTurnCollector c = new ReasoningTurnCollector("general");
        ReasoningCard card = c.build(
                "Attends — on avait essayé Siffle.", "Groq/gpt-oss-20b", 1049, 800);
        assertTrue(card.potentialHallucination);
        assertTrue(card.formatPanel().contains("Fiabilité"));
        ReasoningStore.put("Attends — on avait essayé Siffle.", card);
        assertSame(card, ReasoningStore.findForReply("Attends — on avait essayé Siffle."));
    }

    @Test
    public void reasoningCard_calculatorNotHallucination() {
        ReasoningTurnCollector c = new ReasoningTurnCollector("calc");
        c.noteToolStart("calculator", null);
        c.noteToolEnd("calculator", true, 12, "6.545");
        ReasoningCard card = c.build("119 × 5,5 ÷ 100 = 6,545", "Groq/gpt-oss-20b", 400, 200);
        assertFalse(card.potentialHallucination);
        assertEquals("Calcul", card.intentDetected);
        assertTrue(card.formatPanel().contains("calculator"));
        String path = card.formatCheminement();
        assertTrue(path.contains("calculator"));
        assertTrue(path.contains("✅"));
        assertTrue(path.contains("outil utilisé"));
    }

    @Test
    public void reasoningCard_noToolShowsLlmSeul() {
        ReasoningTurnCollector c = new ReasoningTurnCollector("general");
        ReasoningCard card = c.build("Salut !", "Groq/gpt-oss-20b", 200, 100);
        assertTrue(card.formatCheminement().contains("aucun outil"));
        assertTrue(card.formatPanel().contains("aucun"));
    }

    @Test
    public void setSessionUsed_overridesArchivedSessionTopic() {
        ReasoningTurnCollector c = new ReasoningTurnCollector("diag");
        ContextSnapshot snap = new ContextSnapshot(
                "", "diag", null, null,
                java.util.Arrays.asList("Identité"),
                null, "Briefing du matin");
        c.applySnapshot(snap);
        c.setSessionUsed("Tu as eut des problèmes ?");
        ReasoningCard card = c.build("RAS.", "local", 0, 0);
        assertEquals("Tu as eut des problèmes ?", card.sessionUsed);
    }

    @Test
    public void reasoningCard_localToolWithoutLlmSynthesis() {
        ReasoningTurnCollector c = new ReasoningTurnCollector("diag");
        c.noteToolStart("diag", null);
        c.noteToolEnd("diag", true, 19, "lecture trace locale");
        // Sans markLlmSynthesis : ignore le backend/latence du tour précédent (Groq).
        ReasoningCard card = c.build("RAS.", "Groq/gpt-oss-20b", 1241, 800);
        assertEquals("local", card.backend);
        assertEquals(0L, card.latencyMs);
        assertEquals(0, card.promptChars);
    }

    @Test
    public void reasoningCard_llmSynthesisOverridesStaleMetrics() {
        ReasoningTurnCollector c = new ReasoningTurnCollector("general");
        c.markLlmSynthesis("Groq/gpt-oss-20b", 450, 600);
        ReasoningCard card = c.build("Voici.", "local", 0, 0);
        assertEquals("Groq/gpt-oss-20b", card.backend);
        assertEquals(450L, card.latencyMs);
        assertEquals(600, card.promptChars);
    }
}
