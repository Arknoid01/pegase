package com.pegasuscorp.orbe.voice;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class VoiceTeacherParserTest {

    @Test
    public void parse_apprendsQuandJeDis() {
        VoiceTeacherParser.TeachRequest r = VoiceTeacherParser.parse(
                "Apprends que quand je dis on code, c'est ouvrir Orion");
        assertNotNull(r);
        assertEquals("on code", r.utterance);
        assertTrue(r.actionPhrase.contains("Orion"));
    }

    @Test
    public void actionParts_splitsCompositePhrase() {
        assertEquals(2, VoiceTeacherParser.actionParts(
                "ouvrir Orion puis lancer Spotify").size());
    }
}
