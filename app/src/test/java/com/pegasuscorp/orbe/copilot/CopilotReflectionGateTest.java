package com.pegasuscorp.orbe.copilot;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class CopilotReflectionGateTest {

    private static final String PKG = CopilotPrefs.PKG_YOUTUBE;

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        CopilotPrefs.setScreenAnalysisEnabled(ctx, true);
        CopilotPrefs.enableYouTubeCopilot(ctx);
    }

    @Test
    public void needsReflection_falseWithoutFreshScreen() {
        assertFalse(CopilotReflectionGate.needsReflection(ctx,
                "explique cette page et que dois-je faire"));
    }

    @Test
    public void needsReflection_trueForActionWithScreen() {
        ScreenContextStore.update(ctx, PKG, "Article Kotlin — chapitre 3");

        assertTrue(CopilotReflectionGate.needsReflection(ctx,
                "résume cette page et explique le chapitre"));
    }

    @Test
    public void needsReflection_falseForShortGreeting() {
        ScreenContextStore.update(ctx, PKG, "Accueil YouTube");

        assertFalse(CopilotReflectionGate.needsReflection(ctx, "salut"));
        assertFalse(CopilotReflectionGate.needsReflection(ctx, "merci"));
    }

    @Test
    public void needsReflection_falseForRememberIntent() {
        ScreenContextStore.update(ctx, PKG, "Article important");

        assertFalse(CopilotReflectionGate.needsReflection(ctx, "retiens ça pour plus tard"));
    }

    @Test
    public void needsReflection_trueForMultiStep() {
        ScreenContextStore.update(ctx, PKG, "Paramètres Bluetooth");

        assertTrue(CopilotReflectionGate.needsReflection(ctx,
                "lis ce texte puis explique comment activer le bluetooth"));
    }

    @Test
    public void needsReflection_falseWhenStale() {
        ScreenContextStore.update(ctx, PKG, "Contenu",
                System.currentTimeMillis() - CopilotScreenContext.MAX_AGE_MS - 5_000L);

        assertFalse(CopilotReflectionGate.needsReflection(ctx,
                "explique ce que je vois à l'écran"));
    }
}
