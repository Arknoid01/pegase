package com.pegasuscorp.orbe.diag;

import org.junit.Test;

import static org.junit.Assert.*;

public class DiagScriptsTest {

    @Test
    public void miniSuite_hasThreeDistinctScenarios() {
        assertEquals(3, DiagScripts.miniSuite().size());
        assertTrue(DiagScripts.COOLDOWN_MS >= 8_000L);
        long ids = DiagScripts.miniSuite().stream().map(s -> s.id).distinct().count();
        assertEquals(3, ids);
    }

    @Test
    public void scriptResult_cleanWhenNoAnomaliesOrFailures() {
        DiagScriptResult result = new DiagScriptResult(3, 3, 0, 0, 0, 0, 12, 1800, 45000, null);
        assertTrue(result.clean);
        assertTrue(result.summaryLine().contains("3/3 OK"));
        assertTrue(result.summaryLine().contains("0 anomalie"));
    }

    @Test
    public void scriptResult_notCleanWhenAnomalies() {
        DiagScriptResult result = new DiagScriptResult(3, 2, 1, 0, 0, 2, 20, 9000, 60000, null);
        assertFalse(result.clean);
    }
}
