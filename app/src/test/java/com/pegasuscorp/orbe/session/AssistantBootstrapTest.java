package com.pegasuscorp.orbe.session;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class AssistantBootstrapTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        HomeLoadPolicy.setLazyBootstrapEnabled(context, true);
    }

    @Test
    public void deferAssistantWire_whenLazyEnabled() {
        assertTrue(AssistantBootstrap.deferAssistantWire(context));
    }

    @Test
    public void noDeferAssistantWire_whenLazyDisabled() {
        HomeLoadPolicy.setLazyBootstrapEnabled(context, false);
        assertFalse(AssistantBootstrap.deferAssistantWire(context));
    }

    @Test
    public void needsWireOnColdStart_wakeIntent() {
        Intent intent = new Intent();
        intent.putExtra("wake_activate", true);
        assertTrue(AssistantBootstrap.needsWireOnColdStart(context, intent));
    }

    @Test
    public void needsWireOnColdStart_resumeChatIntent() {
        Intent intent = new Intent();
        intent.putExtra("resume_chat", true);
        assertTrue(AssistantBootstrap.needsWireOnColdStart(context, intent));
    }

    @Test
    public void noWireOnColdStart_plainHome() {
        assertFalse(AssistantBootstrap.needsWireOnColdStart(context, new Intent()));
        assertFalse(AssistantBootstrap.needsWireOnColdStart(context, null));
    }
}
