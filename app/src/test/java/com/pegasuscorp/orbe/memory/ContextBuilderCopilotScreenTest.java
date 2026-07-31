package com.pegasuscorp.orbe.memory;

import android.content.Context;

import com.pegasuscorp.orbe.copilot.CopilotPrefs;
import com.pegasuscorp.orbe.copilot.ScreenContextStore;
import com.pegasuscorp.orbe.session.Channel;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class ContextBuilderCopilotScreenTest {

    private static final String PKG = CopilotPrefs.PKG_YOUTUBE;

    private Context ctx;

    @Before
    public void setUp() {
        MemoryRepository.setAutoMigrateForTests(false);
        MemoryRepository.resetInstanceForTests();
        ctx = RuntimeEnvironment.getApplication();
        CopilotPrefs.setScreenAnalysisEnabled(ctx, true);
        CopilotPrefs.enableYouTubeCopilot(ctx);
    }

    @Test
    public void buildSnapshot_includesScreenBlockOnCopilotChannel() {
        ScreenContextStore.update(ctx, PKG, "Vidéo : tutoriel Kotlin");

        ContextSnapshot snap = ContextBuilder.buildSnapshot(
                ctx, "résume cette page", ContextBuilder.analyzeIntent(ctx, "résume"), Channel.COPILOT);

        assertTrue(snap.promptText.contains("Écran actif"));
        assertTrue(snap.promptText.contains("tutoriel Kotlin"));
        assertEquals(PKG, snap.screenContextLabel);
    }

    @Test
    public void buildSnapshot_omitsScreenBlockOnTextChannel() {
        ScreenContextStore.update(ctx, PKG, "Vidéo : tutoriel Kotlin");

        ContextSnapshot snap = ContextBuilder.buildSnapshot(
                ctx, "résume cette page", ContextBuilder.analyzeIntent(ctx, "résume"), Channel.TEXT);

        assertFalse(snap.promptText.contains("Écran actif"));
        assertTrue(snap.screenContextLabel.isEmpty());
    }
}
