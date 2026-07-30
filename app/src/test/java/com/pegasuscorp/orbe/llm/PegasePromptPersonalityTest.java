package com.pegasuscorp.orbe.llm;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class PegasePromptPersonalityTest {

    @Test
    public void buildSystem_includesPersonalityGuide_notDuplicatedTone() {
        PersonalityGuide.clearCacheForTests();
        String system = PegasePrompt.buildSystem(RuntimeEnvironment.getApplication());
        assertTrue(system.contains("Personnalité Pégase"));
        assertTrue(system.contains("Liste noire"));
        assertTrue(system.contains("pote de cœur"));
        assertTrue(system.contains("ENTRÉE VOCALE"));
        assertFalse(system.contains("Tu es d'abord un pote de cœur"));
    }
}
