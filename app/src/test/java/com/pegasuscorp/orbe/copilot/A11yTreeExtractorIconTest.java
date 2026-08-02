package com.pegasuscorp.orbe.copilot;

import org.junit.Test;

import static org.junit.Assert.*;

public class A11yTreeExtractorIconTest {

    @Test
    public void shortResourceName_stripsPackagePrefix() {
        assertEquals("mic_button",
                A11yTreeExtractor.shortResourceName("com.example:id/mic_button"));
        assertEquals("add", A11yTreeExtractor.shortResourceName("add"));
        assertEquals("", A11yTreeExtractor.shortResourceName(""));
    }

    @Test
    public void worthIconViewIdFallback_idHintsWithoutNode() {
        // node null → false
        assertFalse(A11yTreeExtractor.worthIconViewIdFallback(null, "com.app:id/mic_button"));
    }
}
