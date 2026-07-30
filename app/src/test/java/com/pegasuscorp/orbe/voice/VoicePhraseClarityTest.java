package com.pegasuscorp.orbe.voice;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class VoicePhraseClarityTest {

    @Test
    public void isVague_detectsColloquialPhrases() {
        assertTrue(VoicePhraseClarity.isVague("mets un truc"));
        assertTrue(VoicePhraseClarity.isVague("j'ai envie de musique"));
        assertFalse(VoicePhraseClarity.isVague("mets du Daft Punk sur Spotify"));
    }

    @Test
    public void vaguePhraseRoutesToClarificationNotLlm() {
        Context ctx = RuntimeEnvironment.getApplication();
        VoiceIntentRouter.RoutedIntent r =
                VoiceIntentRouter.analyze(ctx, "mets un truc");
        assertNotNull(r.directToolJson);
        assertTrue(r.needsConfirmation);
        assertEquals("spotify", r.intentHint);
    }

    @Test
    public void buildQuestion_echoesUserPhrase() {
        Context ctx = RuntimeEnvironment.getApplication();
        VoiceIntentRouter.RoutedIntent r = new VoiceIntentRouter.RoutedIntent(
                "mets un truc",
                "{\"tool\":\"spotify\",\"params\":{\"action\":\"play\"}}",
                "spotify", 0.62, true);
        String q = VoiceConfirmation.buildQuestion(ctx, r);
        assertTrue(q.contains("mets un truc"));
        assertTrue(q.toLowerCase().contains("musique"));
    }
}
