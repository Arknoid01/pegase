package com.pegasuscorp.orbe.memory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Résumé d'une session de discussion.
 */
public class SessionSummary {

    public String topic = "";
    public String summary = "";
    public final List<String> importantFacts = new ArrayList<>();
    public final List<String> decisions = new ArrayList<>();
    public final List<String> pendingTopics = new ArrayList<>();
    public String endedAt = "";

    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("topic", topic);
        o.put("summary", summary);
        o.put("important_facts", toArray(importantFacts));
        o.put("decisions", toArray(decisions));
        o.put("pending_topics", toArray(pendingTopics));
        o.put("endedAt", endedAt);
        return o;
    }

    public static SessionSummary fromJson(JSONObject o) {
        SessionSummary s = new SessionSummary();
        s.topic = o.optString("topic", "");
        s.summary = o.optString("summary", "");
        readList(o.optJSONArray("important_facts"), s.importantFacts);
        readList(o.optJSONArray("decisions"), s.decisions);
        readList(o.optJSONArray("pending_topics"), s.pendingTopics);
        s.endedAt = o.optString("endedAt", "");
        return s;
    }

    private static JSONArray toArray(List<String> items) {
        JSONArray arr = new JSONArray();
        for (String item : items) arr.put(item);
        return arr;
    }

    private static void readList(JSONArray arr, List<String> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            out.add(arr.optString(i, ""));
        }
    }
}
