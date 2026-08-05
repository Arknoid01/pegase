package com.pegasuscorp.orbe.copilot;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class CopilotAppHintsStoreTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        ctx.getSharedPreferences("copilot_app_hints", Context.MODE_PRIVATE)
                .edit().clear().apply();
    }

    @Test
    public void whatsApp_seed_strictAndAliases() {
        CopilotAppHints h = CopilotAppHintsStore.get(ctx, "com.whatsapp");
        assertTrue(h.strictTextMatch);
        assertEquals("Envoyer", h.resolveAlias("envoyer"));
        assertEquals("Nouvelle discussion", h.resolveAlias("nouveau message"));
        assertFalse(h.toPromptSection().isEmpty());
        assertTrue(h.toPromptSection().contains("contact"));
    }

    @Test
    public void reddit_seed_distrustA11y() {
        CopilotAppHints h = CopilotAppHintsStore.get(ctx, "com.reddit.frontpage");
        assertTrue(h.distrustA11yClickSuccess);
        assertTrue(h.preferGesture);
        assertTrue(h.toPromptSection().toLowerCase().contains("compose"));
    }

    @Test
    public void brave_seed_webview() {
        CopilotAppHints h = CopilotAppHintsStore.get(ctx, "com.brave.browser");
        assertTrue(h.distrustA11yClickSuccess);
        assertEquals("barre d'adresse", h.resolveAlias("omnibox"));
    }

    @Test
    public void unknownPackage_emptyHints() {
        CopilotAppHints h = CopilotAppHintsStore.get(ctx, "com.example.unknown");
        assertTrue(h.isEmpty());
        assertEquals("Foo", h.resolveAlias("Foo"));
    }

    @Test
    public void addNote_persistsAndMergesWithSeed() {
        CopilotAppHintsStore.addNote(ctx, "com.whatsapp", "Contact « Marie » = chat épinglé.");
        CopilotAppHints h = CopilotAppHintsStore.get(ctx, "com.whatsapp");
        assertTrue(h.strictTextMatch); // seed flag survit
        assertTrue(h.toPromptSection().contains("Marie"));
    }

    @Test
    public void setAlias_overrides() {
        CopilotAppHintsStore.setAlias(ctx, "com.whatsapp", "envoyer", "Send");
        CopilotAppHints h = CopilotAppHintsStore.get(ctx, "com.whatsapp");
        assertEquals("Send", h.resolveAlias("envoyer"));
    }

    @Test
    public void promptBlock_includesHints() {
        CopilotScreenContext.Snapshot snap =
                new CopilotScreenContext.Snapshot("com.whatsapp", "Marie\nSalut", 1_000L);
        CopilotAppHints hints = CopilotAppHintsStore.get(ctx, "com.whatsapp");
        String block = CopilotScreenContext.buildPromptBlock(snap, hints);
        assertTrue(block.contains("Hints a11y"));
        assertTrue(block.contains("com.whatsapp"));
        assertTrue(block.contains("Matching strict"));
    }

    @Test
    public void hayStrictText_exactAndPrefix() {
        assertTrue(A11yUiMatcher.hayStrictText("marie", "Marie"));
        assertTrue(A11yUiMatcher.hayStrictText("marie hier 20 00", "Marie"));
        assertFalse(A11yUiMatcher.hayStrictText(
                "salut marie ca va on se voit demain", "Marie"));
    }
}
