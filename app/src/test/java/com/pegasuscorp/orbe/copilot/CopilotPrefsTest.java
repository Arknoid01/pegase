package com.pegasuscorp.orbe.copilot;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class CopilotPrefsTest {

    @Test
    public void alwaysOn_defaultsTrue() {
        Context ctx = ApplicationProvider.getApplicationContext();
        CopilotPrefs.setAlwaysOn(ctx, false);
        CopilotPrefs.setAlwaysOn(ctx, true);
        assertTrue(CopilotPrefs.isAlwaysOn(ctx));
    }

    @Test
    public void orbPosition_roundTrip() {
        Context ctx = ApplicationProvider.getApplicationContext();
        CopilotPrefs.setOrbPosition(ctx, 120, 340);
        assertEquals(120, CopilotPrefs.getOrbX(ctx));
        assertEquals(340, CopilotPrefs.getOrbY(ctx));
    }

    @Test
    public void bubbleOpen_roundTrip() {
        Context ctx = ApplicationProvider.getApplicationContext();
        CopilotPrefs.setBubbleOpen(ctx, true);
        assertTrue(CopilotPrefs.isBubbleOpen(ctx));
        CopilotPrefs.setBubbleOpen(ctx, false);
        assertFalse(CopilotPrefs.isBubbleOpen(ctx));
    }
}
