package com.pegasuscorp.orbe.memory;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class ContextBuilderSessionTest {

    private Context ctx;

    @Before
    public void setUp() {
        MemoryRepository.setAutoMigrateForTests(false);
        MemoryRepository.resetInstanceForTests();
        // Promotion synchrone + juge coupé : pas de thread ni de LLM en test.
        MemoryConsolidator.setSynchronousForTests(true);
        MemoryUpdateJudge.setEnabledForTests(false);
        ctx = RuntimeEnvironment.getApplication();
        MemoryRepository repo = MemoryRepository.getInstance(ctx);
        while (!repo.getAllSessionSummaries().isEmpty()) {
            repo.removeSessionSummaryAt(0);
        }
    }

    @Test
    public void buildSnapshot_includesDecisionsAndPendingFromLatestSession() {
        SessionSummary summary = new SessionSummary();
        summary.topic = "Projet Orbe";
        summary.summary = "Discussion sur l'architecture mémoire.";
        summary.decisions.add("On garde le scoring composite");
        summary.pendingTopics.add("Tester la consolidation locale");
        MemoryRepository.getInstance(ctx).addSessionSummary(summary);

        ContextSnapshot snapshot = ContextBuilder.buildSnapshot(ctx, "comment avance le projet ?");
        assertTrue(snapshot.promptText.contains("Décisions"));
        assertTrue(snapshot.promptText.contains("scoring composite"));
        assertTrue(snapshot.promptText.contains("En attente"));
        assertTrue(snapshot.promptText.contains("consolidation locale"));
    }
}
