package com.pegasuscorp.orbe.copilot;

import org.junit.Test;

import static org.junit.Assert.*;

public class BrowserSearchTargetTest {

    @Test
    public void looksLikeBrowserSearchTarget_commonLlmLabels() {
        assertTrue(A11yUiMatcher.looksLikeBrowserSearchTarget("Barre d'adresse"));
        assertTrue(A11yUiMatcher.looksLikeBrowserSearchTarget("champ de recherche"));
        assertTrue(A11yUiMatcher.looksLikeBrowserSearchTarget("Demande à Google"));
        assertTrue(A11yUiMatcher.looksLikeBrowserSearchTarget("Rechercher"));
        assertTrue(A11yUiMatcher.looksLikeBrowserSearchTarget("omnibox"));
        assertFalse(A11yUiMatcher.looksLikeBrowserSearchTarget("Vatican"));
        assertFalse(A11yUiMatcher.looksLikeBrowserSearchTarget("Astronomie et espace"));
    }
}
