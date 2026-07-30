package com.pegasuscorp.orbe.llm;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class PersonalityGuideTest {

    @Before
    public void setUp() {
        PersonalityGuide.clearCacheForTests();
    }

    @Test
    public void bannedPhrases_parsedFromMarkdown() {
        String[] banned = PersonalityGuide.parseBannedFromBody(
                "## Liste noire (ne jamais dire)\n"
                + "- n'hésite pas\n"
                + "- excellente question\n"
                + "## Ton contextuel\n");
        assertEquals(2, banned.length);
        assertEquals("n'hésite pas", banned[0]);
        assertEquals("excellente question", banned[1]);
    }

    @Test
    public void promptBlock_loadsAssetWithFewShots() {
        String block = PersonalityGuide.promptBlock(RuntimeEnvironment.getApplication());
        assertTrue(block.contains("Personnalité Pégase"));
        assertTrue(block.contains("Liste noire"));
        assertTrue(block.contains("Few-shots"));
        assertTrue(block.contains("N'hésite pas"));
    }

    @Test
    public void stripBannedPhrases_removesCorporateFillers() {
        String out = PersonalityGuide.stripBannedPhrases(
                "Voici la météo. N'hésite pas à me demander si tu veux plus.");
        assertFalse(out.toLowerCase().contains("n'hésite pas"));
        assertTrue(out.contains("météo"));
    }

    @Test
    public void containsBannedPhrase_detectsGenericAssistant() {
        assertTrue(PersonalityGuide.containsBannedPhrase(
                "N'hésite pas à me demander si tu as besoin d'aide."));
        assertFalse(PersonalityGuide.containsBannedPhrase(
                "Franchement oui, on garde le cache local."));
    }
}
