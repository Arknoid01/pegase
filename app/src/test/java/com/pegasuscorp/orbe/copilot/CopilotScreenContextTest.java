package com.pegasuscorp.orbe.copilot;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Collections;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class CopilotScreenContextTest {

    private static final String PKG = "com.example.app";

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        CopilotPrefs.setScreenAnalysisEnabled(ctx, true);
        CopilotPrefs.setWhitelist(ctx, Collections.singleton(PKG));
    }

    @Test
    public void readFresh_returnsSnapshotWhenRecentAndAllowed() {
        ScreenContextStore.update(ctx, PKG, "Titre article\nParagraphe visible");

        CopilotScreenContext.Snapshot snap = CopilotScreenContext.readFresh(ctx);
        assertNotNull(snap);
        assertEquals(PKG, snap.packageName);
        assertTrue(snap.text.contains("Titre article"));
        assertTrue(snap.ageMs < CopilotScreenContext.MAX_AGE_MS);
    }

    @Test
    public void readFresh_nullWhenAnalysisDisabled() {
        ScreenContextStore.update(ctx, PKG, "Texte");
        CopilotPrefs.setScreenAnalysisEnabled(ctx, false);

        assertNull(CopilotScreenContext.readFresh(ctx));
    }

    @Test
    public void readFresh_nullWhenPackageNotWhitelisted() {
        ScreenContextStore.update(ctx, PKG, "Texte");
        CopilotPrefs.setWhitelist(ctx, Collections.emptySet());

        assertNull(CopilotScreenContext.readFresh(ctx));
    }

    @Test
    public void readFresh_nullWhenStale() {
        ScreenContextStore.update(ctx, PKG, "Texte");
        ctx.getSharedPreferences("copilot_screen_ctx", Context.MODE_PRIVATE)
                .edit()
                .putLong("last_ts", System.currentTimeMillis() - CopilotScreenContext.MAX_AGE_MS - 1_000L)
                .apply();

        assertNull(CopilotScreenContext.readFresh(ctx));
    }

    @Test
    public void buildPromptBlock_includesPackageAndText() {
        CopilotScreenContext.Snapshot snap =
                new CopilotScreenContext.Snapshot(PKG, "Contenu écran", 3_000L);
        String block = CopilotScreenContext.buildPromptBlock(snap);
        assertTrue(block.contains("Écran actif"));
        assertTrue(block.contains(PKG));
        assertTrue(block.contains("Contenu écran"));
        assertTrue(block.contains("ne demande jamais d'identifiant technique"));
    }
}
