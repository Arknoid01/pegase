package com.pegasuscorp.orbe.tools.device;

import org.junit.Test;

import static org.junit.Assert.*;

public class OpenAppNormalizeTest {

    @Test
    public void normalize_collapsesLlmSpacedPackages() {
        assertEquals("com.android.chrome",
                OpenAppTool.normalizeAppQuery("com. android. chrome"));
        assertEquals("com.brave.browser",
                OpenAppTool.normalizeAppQuery("com. brave. browser"));
        assertEquals("com.android.chrome",
                OpenAppTool.normalizeAppQuery("  com.android.chrome  "));
    }

    @Test
    public void normalize_keepsHumanLabels() {
        assertEquals("Chrome", OpenAppTool.normalizeAppQuery("Chrome"));
        assertEquals("Google Chrome", OpenAppTool.normalizeAppQuery("Google Chrome"));
    }

    @Test
    public void looksLikePackageName() {
        assertTrue(OpenAppTool.looksLikePackageName("com.android.chrome"));
        assertFalse(OpenAppTool.looksLikePackageName("com. android. chrome"));
        assertFalse(OpenAppTool.looksLikePackageName("Chrome"));
    }
}
