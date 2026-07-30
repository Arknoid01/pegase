package com.pegasuscorp.orbe.bureau;

import com.pegasuscorp.orbe.tools.ToolResult;

import org.junit.Test;

import static org.junit.Assert.*;

public class BureauMarkdownBrainToolResultTest {

    @Test
    public void materializeToolResult_replacesBareCalculatorJson() {
        String raw = "{\"tool\":\"calculator\",\"params\":{\"expression\":\"12*4\"}}";
        String out = BureauMarkdownBrain.materializeToolResult(raw,
                ToolResult.text("Résultat : 48"));
        assertFalse(out.contains("\"tool\""));
        assertTrue(out.contains("48"));
        assertTrue(out.contains(">"));
    }

    @Test
    public void materializeToolResult_keepsPreambleSpeak() {
        String raw = "> C'est noté.\n{\"tool\":\"calculator\",\"params\":{\"expression\":\"2+2\"}}";
        String out = BureauMarkdownBrain.materializeToolResult(raw, ToolResult.text("4"));
        assertTrue(out.contains("> C'est noté.") || out.contains("> C'est note"));
        assertTrue(out.contains("4"));
        assertFalse(out.contains("calculator"));
    }

    @Test
    public void materializeToolError_stripsJson() {
        String raw = "{\"tool\":\"calculator\",\"params\":{\"expression\":\"x\"}}";
        String out = BureauMarkdownBrain.materializeToolError(raw, "Calcul impossible");
        assertFalse(out.contains("\"tool\""));
        assertTrue(out.contains("Calcul impossible"));
    }
}
