package com.pegasuscorp.orbe.orion;

import org.junit.Test;

import static org.junit.Assert.*;

public class OrionTextDiffTest {

    @Test
    public void unchanged() {
        String d = OrionTextDiff.unified("a.js", "same", "same");
        assertTrue(d.contains("Aucun changement"));
    }

    @Test
    public void showsPlusMinus() {
        String d = OrionTextDiff.unified("a.js", "line1\nold\nline3", "line1\nnew\nline3");
        assertTrue(d.contains("- old"));
        assertTrue(d.contains("+ new"));
        assertTrue(d.contains("  line1"));
    }
}
