package com.pegasuscorp.orbe.voice.handlers;

import org.junit.Test;

import static org.junit.Assert.*;

public class CopilotIntentHandlerTest {

    @Test
    public void looksLikeYoutubeSubtitles() {
        assertTrue(CopilotIntentHandler.looksLikeYoutubeSubtitles(
                "active les sous titres sur youtube"));
        assertTrue(CopilotIntentHandler.looksLikeYoutubeSubtitles(
                "mets les sous titres"));
        assertFalse(CopilotIntentHandler.looksLikeYoutubeSubtitles(
                "lance une video"));
    }
}
