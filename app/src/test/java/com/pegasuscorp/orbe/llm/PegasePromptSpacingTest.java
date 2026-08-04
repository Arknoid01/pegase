package com.pegasuscorp.orbe.llm;

import org.junit.Test;

import static org.junit.Assert.*;

public class PegasePromptSpacingTest {

    @Test
    public void fixFrenchOralSpacing_commonGluedWords() {
        assertEquals("tu vas", PegasePrompt.fixFrenchOralSpacing("tuvas"));
        assertEquals("je suis", PegasePrompt.fixFrenchOralSpacing("jesuis"));
        assertEquals("c'est", PegasePrompt.fixFrenchOralSpacing("cest"));
        assertEquals("ça va", PegasePrompt.fixFrenchOralSpacing("cava"));
    }

    @Test
    public void fixFrenchOralSpacing_gluedCompoundNumbers() {
        assertEquals("soixante-et-unième",
                PegasePrompt.fixFrenchOralSpacing("soixanteetunième"));
        assertEquals("vingt-et-un",
                PegasePrompt.fixFrenchOralSpacing("vingtetun"));
        assertEquals("quatre-vingts",
                PegasePrompt.fixFrenchOralSpacing("quatrevingts"));
        assertEquals("dix-huit",
                PegasePrompt.fixFrenchOralSpacing("dixhuit"));
    }

    @Test
    public void fixFrenchOralSpacing_preservesFrenchDecimals() {
        assertEquals("Ça fait 6,545",
                PegasePrompt.fixFrenchOralSpacing("Ça fait 6,545"));
        assertEquals("Ça fait 6,545",
                PegasePrompt.fixFrenchOralSpacing("Ça fait 6, 545"));
        assertEquals("Bonjour, ça va",
                PegasePrompt.fixFrenchOralSpacing("Bonjour,ça va"));
    }

    @Test
    public void sanitizeForSpeech_stripsMarkdownMarkers() {
        assertEquals("Recette simple",
                PegasePrompt.sanitizeForSpeech("**Recette** simple"));
        assertEquals("Titre et corps",
                PegasePrompt.sanitizeForSpeech("## Titre\net *corps*"));
        assertEquals("code inline",
                PegasePrompt.sanitizeForSpeech("`code` inline"));
    }

    @Test
    public void sanitizeForDisplay_removesThinkingAndMarkdown() {
        String raw = "<think>secret</think> ```json ok ```";
        String out = PegasePrompt.sanitizeForDisplay(raw);
        assertFalse(out.contains("secret"));
        assertFalse(out.contains("```"));
        assertTrue(out.contains("ok"));
    }

    @Test
    public void sanitizeForDisplay_preservesNewlines() {
        String raw = "Ligne un.\nLigne deux.";
        String out = PegasePrompt.sanitizeForDisplay(raw);
        assertTrue(out.contains("\n"));
    }

    @Test
    public void sanitizeForDisplay_removesUnclosedThinking() {
        String raw = "<think>secret reasoning only";
        String out = PegasePrompt.sanitizeForDisplay(raw);
        assertFalse(out.contains("secret"));
        assertFalse(out.contains("redacted_thinking"));
    }
}
