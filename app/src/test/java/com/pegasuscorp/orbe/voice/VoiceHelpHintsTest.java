package com.pegasuscorp.orbe.voice;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class VoiceHelpHintsTest {

    @Test
    public void detectsAideKeyword() {
        assertTrue(VoiceHelpHints.isHelpRequest("aide"));
        assertTrue(VoiceHelpHints.isHelpRequest("Aide s'il te plaît"));
    }
}
