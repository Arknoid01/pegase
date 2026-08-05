package com.pegasuscorp.orbe.copilot;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CopilotHintsLearnerTest {

    @Test
    public void parseProposalJson_notesAndAliases() {
        String raw = "{\"notes\":[\"Pour envoyer, clique le bouton Envoyer\"],"
                + "\"aliases\":[{\"from\":\"envoyer\",\"to\":\"Envoyer\"}]}";
        CopilotHintsLearner.Proposal p = CopilotHintsLearner.parseProposalJson(raw);
        assertFalse(p.isEmpty());
        assertEquals(1, p.notes.size());
        assertEquals(1, p.aliases.size());
        assertEquals("envoyer", p.aliases.get(0)[0]);
        assertEquals("Envoyer", p.aliases.get(0)[1]);
    }

    @Test
    public void parseProposalJson_emptyAndMarkdownFence() {
        assertTrue(CopilotHintsLearner.parseProposalJson("{\"notes\":[],\"aliases\":[]}").isEmpty());
        String fenced = "Voici:\n```json\n{\"notes\":[\"Barre d adresse = omnibox\"],\"aliases\":[]}\n```";
        CopilotHintsLearner.Proposal p = CopilotHintsLearner.parseProposalJson(fenced);
        assertEquals(1, p.notes.size());
    }

    @Test
    public void parseProposalJson_rejectsViewIds() {
        String raw = "{\"notes\":[\"com.android.chrome:id/url_bar\"],"
                + "\"aliases\":[{\"from\":\"x\",\"to\":\"pkg:id/btn\"}]}";
        assertTrue(CopilotHintsLearner.parseProposalJson(raw).isEmpty());
    }

    @Test
    public void buildLearnPrompt_mentionsJsonRules() {
        String p = CopilotHintsLearner.buildLearnPrompt(
                "com.android.chrome", "chercher wiki", "1. click → ok", "ok");
        assertTrue(p.contains("notes"));
        assertTrue(p.contains("JAMAIS"));
        assertTrue(p.contains("com.android.chrome"));
    }
}
