package com.pegasuscorp.orbe.copilot;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CopilotNotificationSummarizerTest {

    @Test
    public void messaging_usesPegasePhrase() {
        String msg = CopilotNotificationSummarizer.summarize(
                "com.whatsapp", "WhatsApp",
                "Marine", "Tu viens ce soir ?");
        assertEquals("Marine t'a écrit : Tu viens ce soir ?", msg);
    }

    @Test
    public void stripsGroupMessageCount() {
        String msg = CopilotNotificationSummarizer.summarize(
                "com.whatsapp", "WhatsApp",
                "Famille (3 messages)", "On arrive");
        assertEquals("Famille t'a écrit : On arrive", msg);
    }

    @Test
    public void gmail_extractsSender() {
        String msg = CopilotNotificationSummarizer.summarize(
                "com.google.android.gm", "Gmail",
                "Marine: Réunion demain", "Détails dans le corps");
        assertEquals("Marine t'a écrit : Détails dans le corps", msg);
    }

    @Test
    public void fallback_usesAppLabel() {
        String msg = CopilotNotificationSummarizer.summarize(
                "com.example.app", "MonApp",
                "Titre", "Contenu");
        assertEquals("Titre : Contenu", msg);
    }

    @Test
    public void empty_returnsEmpty() {
        assertEquals("", CopilotNotificationSummarizer.summarize(
                "com.whatsapp", "WhatsApp", "", ""));
    }
}
