package com.pegasuscorp.orbe.diag;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class CopilotUiDiagTest {

    @Before
    public void setUp() {
        Trace.init(RuntimeEnvironment.getApplication());
        Trace.clear(RuntimeEnvironment.getApplication());
    }

    @Test
    public void copilotUi_writesKindCategoryAndPkg() throws Exception {
        Trace.copilotUi("matcher_miss", "click_not_found", "Cible introuvable",
                "com.whatsapp", "Envoyer");
        Trace.flushForTests();
        String jsonl = new String(Files.readAllBytes(Trace.file().toPath()), StandardCharsets.UTF_8);
        assertTrue(jsonl.contains("copilot_ui"));
        assertTrue(jsonl.contains("matcher_miss"));
        assertTrue(jsonl.contains("COPILOT_MATCHER"));
        assertTrue(jsonl.contains("com.whatsapp"));
        assertTrue(jsonl.contains("Envoyer"));
    }

    @Test
    public void detectCopilotMatcherMiss_flagsEvent() throws Exception {
        List<JSONObject> events = new ArrayList<>();
        events.add(copilot(1000, "matcher_miss", "com.whatsapp", "mic"));
        List<JSONObject> found = DiagReport.detectCopilotMatcherMiss(events);
        assertEquals(1, found.size());
        assertEquals("copilot_matcher_miss", found.get(0).optString("type"));
        assertTrue(found.get(0).optString("explanation").contains("whatsapp"));
    }

    @Test
    public void detectCopilotWhitelistBlock_flagsEvent() throws Exception {
        List<JSONObject> events = new ArrayList<>();
        events.add(copilot(1000, "whitelist_block", "com.bank.app", ""));
        assertEquals(1, DiagReport.detectCopilotWhitelistBlock(events).size());
        assertEquals("copilot_whitelist_block",
                DiagReport.detectCopilotWhitelistBlock(events).get(0).optString("type"));
    }

    @Test
    public void detectCopilotConfirmStale_whenAskUnansweredBeyondWindow() throws Exception {
        List<JSONObject> events = new ArrayList<>();
        events.add(copilot(1_000L, "confirm_ask", "com.whatsapp", "Supprimer"));
        // Fin de session 3 min plus tard, sans ok/cancel
        events.add(new JSONObject().put("t", 1_000L + 180_000L).put("type", "user_message"));
        List<JSONObject> found = DiagReport.detectCopilotConfirmStale(events);
        assertEquals(1, found.size());
        assertEquals("copilot_confirm_stale", found.get(0).optString("type"));
    }

    @Test
    public void detectCopilotConfirmStale_ignoresResolvedAsk() throws Exception {
        List<JSONObject> events = new ArrayList<>();
        events.add(copilot(1_000L, "confirm_ask", "com.whatsapp", "Supprimer"));
        events.add(copilot(2_000L, "confirm_ok", "com.whatsapp", "Supprimer"));
        events.add(new JSONObject().put("t", 1_000L + 180_000L).put("type", "user_message"));
        assertEquals(0, DiagReport.detectCopilotConfirmStale(events).size());
    }

    @Test
    public void detectCopilotA11yDown_flagsUnavailableAndDisconnected() throws Exception {
        List<JSONObject> events = new ArrayList<>();
        events.add(copilot(1000, "a11y_unavailable", "", ""));
        events.add(copilot(2000, "a11y_disconnected", "", ""));
        assertEquals(2, DiagReport.detectCopilotA11yDown(events).size());
    }

    private static JSONObject copilot(long t, String kind, String pkg, String target)
            throws Exception {
        return new JSONObject()
                .put("t", t)
                .put("type", "copilot_ui")
                .put("kind", kind)
                .put("tool", "ui_action")
                .put("pkg", pkg)
                .put("target", target)
                .put("reason", kind)
                .put("detail", "")
                .put("category", "COPILOT_MATCHER");
    }
}
