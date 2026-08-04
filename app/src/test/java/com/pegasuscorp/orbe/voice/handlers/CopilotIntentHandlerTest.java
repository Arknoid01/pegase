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

    @Test
    public void looksLikeBackCommand() {
        assertTrue(CopilotIntentHandler.looksLikeBackCommand("retour"));
        assertTrue(CopilotIntentHandler.looksLikeBackCommand("retour arriere"));
        assertTrue(CopilotIntentHandler.looksLikeBackCommand("back"));
        assertFalse(CopilotIntentHandler.looksLikeBackCommand("retour sur youtube"));
        assertFalse(CopilotIntentHandler.looksLikeBackCommand(null));
    }
}
