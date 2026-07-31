package com.pegasuscorp.orbe.voice;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class PegaseVisualStateHubTest {

    @Before
    @After
    public void reset() {
        PegaseVisualStateHub.resetForTests();
    }

    @Test
    public void derivePhase_thinkingOverridesListening() {
        PegaseWakeController.setMicListening(true);
        PegaseWakeController.setAssistantThinking(true);
        assertEquals(PegaseVisualPhase.THINKING, PegaseVisualStateHub.derivePhase());
    }

    @Test
    public void derivePhase_listeningWhenMicOnly() {
        PegaseWakeController.setMicListening(true);
        assertEquals(PegaseVisualPhase.MIC_LISTENING, PegaseVisualStateHub.derivePhase());
    }

    @Test
    public void refresh_notifiesListeners() {
        final PegaseVisualPhase[] seen = new PegaseVisualPhase[1];
        PegaseVisualStateHub.addListener(phase -> seen[0] = phase);
        PegaseWakeController.setAssistantThinking(true);
        PegaseWakeController.setAssistantThinking(true);
        PegaseVisualStateHub.refresh();
        assertEquals(PegaseVisualPhase.THINKING, seen[0]);
        assertEquals(PegaseVisualPhase.THINKING, PegaseVisualStateHub.currentPhase());
    }

    @Test
    public void refresh_idleAfterClear() {
        PegaseWakeController.setAssistantThinking(true);
        PegaseVisualStateHub.refresh();
        PegaseWakeController.setAssistantThinking(false);
        PegaseVisualStateHub.refresh();
        assertEquals(PegaseVisualPhase.IDLE, PegaseVisualStateHub.currentPhase());
    }
}
