package com.pegasuscorp.orbe.copilot;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class UiLoopDecisionTest {

    @Test
    public void parse_clickAction() {
        UiLoopDecision d = UiLoopDecision.parse(
                "{\"action\":\"click\",\"target\":\"Rechercher\"}");
        assertEquals(UiLoopDecision.Kind.ACTION, d.kind);
        assertEquals("click", d.action);
        assertEquals("Rechercher", d.params.optString("target"));
    }

    @Test
    public void parse_finishOk() {
        UiLoopDecision d = UiLoopDecision.parse(
                "{\"finish_task\":\"ok\",\"reason\":\"Page Wikipedia ouverte\"}");
        assertEquals(UiLoopDecision.Kind.FINISH_OK, d.kind);
        assertTrue(d.reason.contains("Wikipedia"));
    }

    @Test
    public void parse_finishFail() {
        UiLoopDecision d = UiLoopDecision.parse(
                "{\"finish_task\":\"fail\",\"reason\":\"Pas de champ recherche\"}");
        assertEquals(UiLoopDecision.Kind.FINISH_FAIL, d.kind);
    }

    @Test
    public void parse_finishNeedConfirm() {
        UiLoopDecision d = UiLoopDecision.parse(
                "{\"finish_task\":\"need_confirm\",\"reason\":\"Envoyer le message ?\"}");
        assertEquals(UiLoopDecision.Kind.FINISH_NEED_CONFIRM, d.kind);
    }

    @Test
    public void parse_markdownFence() {
        UiLoopDecision d = UiLoopDecision.parse(
                "Voici:\n```json\n{\"action\":\"back\"}\n```\n");
        assertEquals(UiLoopDecision.Kind.ACTION, d.kind);
        assertEquals("back", d.action);
    }

    @Test
    public void parse_openNormalized() {
        UiLoopDecision d = UiLoopDecision.parse(
                "{\"action\":\"open_app\",\"name\":\"Chrome\"}");
        assertEquals(UiLoopDecision.Kind.ACTION, d.kind);
        assertEquals("open", d.action);
        assertEquals("Chrome", d.params.optString("name"));
    }

    @Test
    public void parse_toolWrapper() {
        UiLoopDecision d = UiLoopDecision.parse(
                "{\"tool\":\"finish_task\",\"params\":{\"status\":\"ok\",\"reason\":\"fait\"}}");
        assertEquals(UiLoopDecision.Kind.FINISH_OK, d.kind);
        assertEquals("fait", d.reason);
    }

    @Test
    public void parse_invalid() {
        UiLoopDecision d = UiLoopDecision.parse("je clique sur rechercher");
        assertEquals(UiLoopDecision.Kind.INVALID, d.kind);
    }

    @Test
    public void buildTurnPrompt_includesGoalAndScreen() {
        UiLoopRunner.ScreenSnap snap =
                new UiLoopRunner.ScreenSnap("com.android.chrome", "Rechercher\nOmnibox");
        CopilotAppHints hints = CopilotAppHintsStore.builtin("com.android.chrome");
        String p = UiLoopRunner.buildTurnPrompt(
                "cherche Wikipedia", snap, hints, 2, "ok click", "1. open → ok");
        assertTrue(p.contains("cherche Wikipedia"));
        assertTrue(p.contains("Omnibox"));
        assertTrue(p.contains("finish_task"));
        assertTrue(p.contains("Hints a11y") || p.contains("WebView"));
    }
}
