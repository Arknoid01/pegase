package com.pegasuscorp.orbe.tools.knowledge;

import com.pegasuscorp.orbe.tools.ToolResult;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

public class TavilySearchServiceTest {

    @Test
    public void toLlmContext_includesSourcesAndQuestion() {
        TavilySearchService.Bundle bundle = new TavilySearchService.Bundle(
                "psg score",
                "PSG gagne 2-1",
                Collections.singletonList(
                        new TavilySearchService.SourceSnippet(
                                "Match PSG", "lequipe.fr", "Victoire 2-1 ce soir")));

        String ctx = bundle.toLlmContext("Score du PSG ce soir ?");

        assertTrue(ctx.contains("Score du PSG ce soir"));
        assertTrue(ctx.contains("PSG gagne 2-1"));
        assertTrue(ctx.contains("lequipe.fr"));
        assertTrue(ctx.contains("Victoire 2-1"));
    }

    @Test
    public void toolResult_contextForSynthesis_prefersLlmContext() {
        ToolResult result = ToolResult.text("Réponse courte affichée.", "Extraits web bruts");

        assertEquals("Extraits web bruts", result.contextForSynthesis());
        assertEquals("Réponse courte affichée.", result.text);
        assertEquals("Réponse courte affichée.", result.wireText());
    }
}
