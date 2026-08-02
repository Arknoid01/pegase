package com.pegasuscorp.orbe.voice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VoiceSessionReplayPhraseTest {

    @Test
    public void replayOnlyShortPhrases() {
        assertTrue(VoiceSessionContext.isReplayLastPhrase("encore"));
        assertTrue(VoiceSessionContext.isReplayLastPhrase("pareil"));
        assertTrue(VoiceSessionContext.isReplayLastPhrase("la meme chose"));
        assertFalse(VoiceSessionContext.isReplayLastPhrase("ouvre encore cursor"));
        assertFalse(VoiceSessionContext.isReplayLastPhrase("ouvre cursor"));
        assertFalse(VoiceSessionContext.isReplayLastPhrase("relance cursor"));
    }
}
