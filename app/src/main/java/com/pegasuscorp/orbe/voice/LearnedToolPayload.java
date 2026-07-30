package com.pegasuscorp.orbe.voice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Format JSON des intentions simples, composites et slots du corpus. */
public final class LearnedToolPayload {

    public static final String COMPOSITE_TOOL = "composite";

    private LearnedToolPayload() {}

    public static boolean isComposite(String toolJson) {
        if (toolJson == null || toolJson.isEmpty()) return false;
        try {
            JSONObject root = new JSONObject(toolJson.trim());
            return COMPOSITE_TOOL.equals(root.optString("tool", ""));
        } catch (Exception e) {
            return false;
        }
    }

    public static String label(String toolJson) {
        if (toolJson == null) return "";
        try {
            JSONObject root = new JSONObject(toolJson.trim());
            if (isComposite(toolJson)) {
                return root.optJSONObject("params").optString("label", "séquence");
            }
            String tool = root.optString("tool", "");
            String hint = tool.isEmpty() ? "action" : tool;
            JSONObject params = root.optJSONObject("params");
            if (params != null && params.has("name")) {
                return hint + " · " + params.optString("name");
            }
            if (params != null && params.has("action")) {
                return hint + " · " + params.optString("action");
            }
            return hint;
        } catch (Exception e) {
            return "action";
        }
    }

    public static String buildComposite(List<String> stepToolJsons, String label) throws Exception {
        JSONArray steps = new JSONArray();
        for (String stepJson : stepToolJsons) {
            JSONObject step = new JSONObject(stepJson.trim());
            steps.put(new JSONObject()
                    .put("tool", step.optString("tool", ""))
                    .put("params", step.optJSONObject("params")));
        }
        JSONObject params = new JSONObject()
                .put("label", label == null ? "" : label.trim())
                .put("steps", steps);
        return new JSONObject()
                .put("tool", COMPOSITE_TOOL)
                .put("params", params)
                .toString();
    }

    public static JSONArray steps(String toolJson) throws Exception {
        JSONObject root = new JSONObject(toolJson.trim());
        JSONObject params = root.getJSONObject("params");
        return params.getJSONArray("steps");
    }

    public static List<String> splitCompositePhrase(String phrase) {
        List<String> parts = new ArrayList<>();
        if (phrase == null || phrase.trim().isEmpty()) return parts;
        String[] chunks = phrase.split("(?i)\\s+(?:puis|ensuite|et aussi|apres|après)\\s+");
        for (String chunk : chunks) {
            String t = chunk.trim();
            if (!t.isEmpty()) parts.add(t);
        }
        if (parts.isEmpty()) parts.add(phrase.trim());
        return parts;
    }
}
