package com.pegasuscorp.orbe.tools;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.EnumSet;

@RunWith(RobolectricTestRunner.class)
public class ToolTogglesTest {

    private final Context ctx = RuntimeEnvironment.getApplication();

    @After
    public void tearDown() {
        ToolToggles.setDisabled(ctx, "sms", false);
    }

    private static boolean listed(ToolRegistry r, String id) {
        for (Tool t : r.listTools(EnumSet.allOf(ToolTag.class))) if (t.id().equals(id)) return true;
        return false;
    }

    @Test
    public void outilDesactive_disparaitDesSchemasEtDuPrompt_maisResteTrouvable() {
        ToolRegistry r = new ToolRegistry();
        assertTrue(listed(r, "sms"));

        ToolToggles.setDisabled(ctx, "SMS", true);

        assertTrue(ToolToggles.isDisabled("sms"));
        assertFalse(listed(r, "sms"));
        assertFalse(r.buildPromptSection().contains("  • sms("));
        assertNotNull(r.findById("sms"));   // l'écran Outils peut encore l'afficher

        ToolToggles.setDisabled(ctx, "sms", false);
        assertFalse(ToolToggles.isDisabled("sms"));
        assertTrue(listed(r, "sms"));
    }

    @Test
    public void reload_relitLesPreferences() {
        ToolToggles.setDisabled(ctx, "sms", true);
        ToolToggles.reload(ctx);
        assertTrue(ToolToggles.isDisabled("sms"));
    }
}
