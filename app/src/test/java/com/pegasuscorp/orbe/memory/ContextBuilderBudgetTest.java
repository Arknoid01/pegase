package com.pegasuscorp.orbe.memory;

import org.junit.Test;

import static org.junit.Assert.*;

public class ContextBuilderBudgetTest {

    @Test
    public void assembleWithinBudget_keepsClockAndDropsTail() {
        String clock = "CLOCK_" + repeat("c", 100);
        String atlas = "\nATLAS_" + repeat("a", 200);
        String memories = "\nMEM_" + repeat("m", 400);
        String screen = "\nSCREEN_" + repeat("s", 800);
        String out = ContextBuilder.assembleWithinBudget(350,
                clock, atlas, memories, "", "", "", "", screen);
        assertTrue(out.startsWith("CLOCK_"));
        assertTrue(out.length() <= 350);
        assertFalse(out.contains("SCREEN_"));
    }

    @Test
    public void assembleWithinBudget_emptyBlocksSkipped() {
        String out = ContextBuilder.assembleWithinBudget(500,
                "A", "", "B", "", "", "", "", "");
        assertEquals("AB", out);
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
