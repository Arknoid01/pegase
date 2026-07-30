package com.pegasuscorp.orbe.memory;

import com.pegasuscorp.orbe.chat.ChatBackend;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class LocalSessionExtractorTest {

    @Test
    public void enrich_extractsFactsDecisionsAndPending() {
        SessionSummary summary = new SessionSummary();
        LocalSessionExtractor.enrich(summary, Arrays.asList(
                new ChatBackend.Turn(true, "Retiens que je préfère le jazz le soir"),
                new ChatBackend.Turn(false, "Noté."),
                new ChatBackend.Turn(true, "D'accord pour partir sur React Native"),
                new ChatBackend.Turn(true, "Rappelle-moi de vérifier le build demain")
        ));
        assertEquals(1, summary.importantFacts.size());
        assertTrue(summary.importantFacts.get(0).contains("jazz"));
        assertEquals(1, summary.decisions.size());
        assertTrue(summary.decisions.get(0).contains("React"));
        assertEquals(1, summary.pendingTopics.size());
        assertTrue(summary.pendingTopics.get(0).contains("build"));
    }

    @Test
    public void enrich_deduplicatesSimilarItems() {
        SessionSummary summary = new SessionSummary();
        LocalSessionExtractor.enrich(summary, Arrays.asList(
                new ChatBackend.Turn(true, "Retiens que j'aime le café"),
                new ChatBackend.Turn(true, "Retiens que j'aime le café")
        ));
        assertEquals(1, summary.importantFacts.size());
    }
}
