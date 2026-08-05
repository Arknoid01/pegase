package com.pegasuscorp.orbe.copilot;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** Robolectric requis : {@code CopilotReflectionPlanner} s'appuie sur {@code TextUtils}. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CopilotReflectionPlannerTest {

    @Test
    public void buildReflectionPrompt_includesScreenAndUserText() {
        CopilotScreenContext.Snapshot snap = new CopilotScreenContext.Snapshot(
                "com.example.app", "Titre visible\nParagraphe", 2_000L);
        String prompt = CopilotReflectionPlanner.buildReflectionPrompt(snap,
                "que dois-je faire sur cette page ?");

        assertTrue(prompt.contains("planificateur interne"));
        assertTrue(prompt.contains("Titre visible"));
        assertTrue(prompt.contains("que dois-je faire"));
        assertTrue(prompt.contains("NE RÉPONDS PAS"));
    }

    @Test
    public void buildPayloadPrefix_wrapsPlan() {
        String prefix = CopilotReflectionPlanner.buildPayloadPrefix(
                "- Voir le titre\n- Proposer un résumé");
        assertTrue(prefix.startsWith(CopilotReflectionPlanner.PAYLOAD_MARKER));
        assertTrue(prefix.contains("Proposer un résumé"));
        assertTrue(prefix.endsWith("\n\n"));
    }

    @Test
    public void buildPayloadPrefix_emptyForBlankPlan() {
        assertEquals("", CopilotReflectionPlanner.buildPayloadPrefix(""));
        assertEquals("", CopilotReflectionPlanner.buildPayloadPrefix(null));
    }
}
