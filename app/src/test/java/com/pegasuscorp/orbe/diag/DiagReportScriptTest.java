package com.pegasuscorp.orbe.diag;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class DiagReportScriptTest {

    @Test
    public void detectScriptHistoryPollution_flagsOversizedFirstSend() throws Exception {
        List<JSONObject> events = suiteStartPlusHistory(9);
        assertEquals(1, DiagReport.detectScriptHistoryPollution(events).size());
    }

    @Test
    public void detectScriptHistoryPollution_cleanWhenSizeOne() throws Exception {
        List<JSONObject> events = suiteStartPlusHistory(1);
        assertEquals(0, DiagReport.detectScriptHistoryPollution(events).size());
    }

    @Test
    public void detectBureauLlmFallback_flagsLocalReplacements() throws Exception {
        List<JSONObject> events = new ArrayList<>();
        JSONObject edit = new JSONObject();
        edit.put("t", 1000);
        edit.put("type", "bureau_edit");
        edit.put("fallback", true);
        edit.put("markdown_chars", 12);
        edit.put("speak", "Section ajoutée en local.");
        events.add(edit);
        assertEquals(1, DiagReport.detectBureauLlmFallback(events).size());
    }

    @Test
    public void detectPastReferenceHallucination_fromReasoningCard() throws Exception {
        List<JSONObject> events = new ArrayList<>();
        JSONObject card = new JSONObject();
        card.put("t", 2000);
        card.put("type", "reasoning_card");
        card.put("potentialHallucination", true);
        card.put("hallucination_reason", "Aucune source — on avait essayé");
        card.put("intent", "Conversation");
        card.put("context_chunks", 0);
        card.put("cheminement", "Demande → LLM seul");
        events.add(card);
        List<JSONObject> found = DiagReport.detectPastReferenceHallucination(events);
        assertEquals(1, found.size());
        assertEquals("past_reference_no_source", found.get(0).optString("type"));
    }

    @Test
    public void countHallucinations_byType() throws Exception {
        List<JSONObject> events = new ArrayList<>();
        JSONObject card = new JSONObject();
        card.put("type", "reasoning_card");
        card.put("potentialHallucination", true);
        events.add(card);
        JSONObject phantom = new JSONObject();
        phantom.put("type", "phantom_blocked");
        events.add(phantom);
        org.json.JSONArray anomalies = new org.json.JSONArray();
        org.json.JSONObject a = new org.json.JSONObject();
        a.put("type", "phantom_action");
        anomalies.put(a);
        org.json.JSONObject counts = DiagReport.countHallucinations(events, anomalies);
        assertEquals(3, counts.optInt("total"));
        assertEquals(1, counts.optJSONObject("by_type").optInt("past_reference_no_source"));
        assertEquals(2, counts.optJSONObject("by_type").optInt("phantom_action"));
    }

    private static List<JSONObject> suiteStartPlusHistory(int historySize) throws Exception {
        List<JSONObject> events = new ArrayList<>();
        JSONObject start = new JSONObject();
        start.put("t", 1);
        start.put("type", "script_suite_start");
        start.put("memory_cleared", true);
        start.put("suite_id", "mini_diag_v2");
        events.add(start);

        JSONObject history = new JSONObject();
        history.put("t", 2);
        history.put("type", "history");
        history.put("label", "before_send");
        history.put("size", historySize);
        events.add(history);
        return events;
    }
}
