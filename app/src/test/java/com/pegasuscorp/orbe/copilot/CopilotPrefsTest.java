package com.pegasuscorp.orbe.copilot;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CopilotPrefsTest {

    @Test
    public void alwaysOn_defaultsTrue() {
        Context ctx = ApplicationProvider.getApplicationContext();
        CopilotPrefs.setAlwaysOn(ctx, true);
        assertTrue(CopilotPrefs.isAlwaysOn(ctx));
    }

    @Test
    public void whitelist_strict() {
        Context ctx = ApplicationProvider.getApplicationContext();
        CopilotPrefs.setWhitelist(ctx, java.util.Collections.emptySet());
        assertFalse(CopilotPrefs.isPackageAllowed(ctx, CopilotPrefs.PKG_YOUTUBE));
        CopilotPrefs.enableYouTubeCopilot(ctx);
        assertTrue(CopilotPrefs.isPackageAllowed(ctx, CopilotPrefs.PKG_YOUTUBE));
        assertTrue(CopilotPrefs.isScreenAnalysisEnabled(ctx));
    }

    @Test
    public void orbPosition_roundTrip() {
        Context ctx = ApplicationProvider.getApplicationContext();
        CopilotPrefs.setOrbPosition(ctx, 120, 340);
        assertEquals(120, CopilotPrefs.getOrbX(ctx));
        assertEquals(340, CopilotPrefs.getOrbY(ctx));
    }
}
