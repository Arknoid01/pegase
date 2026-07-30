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
    public void promptBlock_loadsAssetWithFewShots() {
        String block = PersonalityGuide.promptBlock(RuntimeEnvironment.getApplication());
        assertTrue(block.contains("Personnalité Pégase"));
        assertTrue(block.contains("Liste noire"));
        assertTrue(block.contains("Few-shots"));
        assertTrue(block.contains("N'hésite pas"));
    }

    @Test
    public void containsBannedPhrase_detectsGenericAssistant() {
        assertTrue(PersonalityGuide.containsBannedPhrase(
                "N'hésite pas à me demander si tu as besoin d'aide."));
        assertFalse(PersonalityGuide.containsBannedPhrase(
                "Franchement oui, on garde le cache local."));
    }
}
