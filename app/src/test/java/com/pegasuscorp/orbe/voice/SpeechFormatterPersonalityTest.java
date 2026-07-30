package com.pegasuscorp.orbe.voice;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class SpeechFormatterPersonalityTest {

    @Test
    public void formatChunks_stripsBannedPhrases() {
        List<String> chunks = SpeechFormatter.formatChunks(
                RuntimeEnvironment.getApplication(),
                "Il fait beau. N'hésite pas à me demander la météo de demain.");
        assertFalse(chunks.isEmpty());
        String joined = String.join(" ", chunks).toLowerCase();
        assertFalse(joined.contains("n'hésite pas"));
        assertTrue(joined.contains("beau"));
    }
}
