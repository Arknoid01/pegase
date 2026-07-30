package com.pegasuscorp.orbe.ui;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ThinkingViewTest {

    private ThinkingView view;

    @Before
    public void setUp() {
        Context ctx = RuntimeEnvironment.getApplication();
        view = new ThinkingView(ctx);
    }

    @Test
    public void toolLabel_frenchNames() {
        assertEquals("météo", ThinkingView.toolLabel("weather"));
        assertEquals("recherche web", ThinkingView.toolLabel("search"));
        assertEquals("Wikipedia", ThinkingView.toolLabel("wikipedia"));
        assertEquals("alarme", ThinkingView.toolLabel("alarm"));
        assertEquals("agenda", ThinkingView.toolLabel("agenda"));
        assertEquals("GitHub", ThinkingView.toolLabel("git_commit"));
        assertEquals("custom_x", ThinkingView.toolLabel("custom_x"));
    }

    @Test
    public void toolStart_updatesText() {
        view.onToolStart("weather");
        ShadowLooper.idleMainLooper();
        assertTrue(view.isShowing());
        String t = view.getDisplayedText();
        assertTrue(t.contains("météo"));
        assertTrue(t.contains("🔄"));
    }

    @Test
    public void toolEnd_showsCheck() {
        view.onToolStart("search");
        view.onToolEnd("search", true);
        ShadowLooper.idleMainLooper();
        String t = view.getDisplayedText();
        assertTrue(t.contains("recherche web"));
        assertTrue(t.contains("✅"));
        assertFalse(t.contains("🔄"));
    }

    @Test
    public void llmStart_showsThinking() {
        view.onLlmStart();
        ShadowLooper.idleMainLooper();
        assertTrue(view.isShowing());
        assertTrue(view.getDisplayedText().contains("Pégase réfléchit"));
    }

    @Test
    public void toolsThenLlm_keepsToolSummary() {
        view.onToolStart("weather");
        view.onToolEnd("weather", true);
        view.onLlmStart();
        ShadowLooper.idleMainLooper();
        String t = view.getDisplayedText();
        assertTrue(t.contains("météo"));
        assertTrue(t.contains("Pégase réfléchit"));
    }

    @Test
    public void onComplete_hides() {
        view.onLlmStart();
        ShadowLooper.idleMainLooper();
        view.onComplete();
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        assertEquals(android.view.View.GONE, view.getVisibility());
    }

    @Test
    public void onError_showsRedMessage() {
        view.onError();
        ShadowLooper.idleMainLooper();
        assertTrue(view.getDisplayedText().contains("erreur"));
    }
}
