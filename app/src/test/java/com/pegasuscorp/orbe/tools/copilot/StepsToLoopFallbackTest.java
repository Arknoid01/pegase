package com.pegasuscorp.orbe.tools.copilot;

import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class StepsToLoopFallbackTest {

    @Test
    public void shouldFallback_stepMiss() {
        assertTrue(StepsToLoopFallback.shouldFallback(
                "Étape 2/3 (click) : Je ne trouve pas cet élément à l'écran."));
        assertTrue(StepsToLoopFallback.shouldFallback(
                "Étape 1/2 (type) : Je ne trouve pas le champ à remplir."));
    }

    @Test
    public void shouldFallback_notOnCancelOrWhitelist() {
        assertFalse(StepsToLoopFallback.shouldFallback("Clic annulé."));
        assertFalse(StepsToLoopFallback.shouldFallback(
                "Cette app n'est pas autorisée pour le copilote (com.x)."));
        assertFalse(StepsToLoopFallback.shouldFallback("Séquence vide — indique steps."));
        assertFalse(StepsToLoopFallback.shouldFallback("Trop d'étapes (max 6)."));
    }

    @Test
    public void goalFromSteps_readsActions() throws Exception {
        JSONArray steps = new JSONArray(
                "[{\"action\":\"open\",\"name\":\"Chrome\"},"
                        + "{\"action\":\"click\",\"target\":\"barre d'adresse\"},"
                        + "{\"action\":\"type\",\"value\":\"Wikipedia\"}]");
        String goal = StepsToLoopFallback.goalFromSteps(steps);
        assertTrue(goal.contains("Chrome"));
        assertTrue(goal.contains("barre d'adresse"));
        assertTrue(goal.contains("Wikipedia"));
    }

    @Test
    public void traceFromFailure_includesPlanAndError() throws Exception {
        JSONArray steps = new JSONArray(
                "[{\"action\":\"click\",\"target\":\"Rechercher\"}]");
        String t = StepsToLoopFallback.traceFromFailure(steps,
                "Étape 1/1 (click) : Je ne trouve pas cet élément");
        assertTrue(t.contains("Rechercher"));
        assertTrue(t.contains("Échec"));
        assertTrue(t.contains("ne trouve pas"));
    }
}
