package com.pegasuscorp.orbe.chat;

import com.pegasuscorp.orbe.llm.PegasePrompt;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Parse une réponse chat/completions Groq (contenu + tool_calls). */
public final class GroqCompletionParser {

    private GroqCompletionParser() {}

    static LlmReply parse(String bodyText) throws Exception {
        JSONObject json = new JSONObject(bodyText);
        JSONObject message = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message");

        String content = message.optString("content", null);
        if (content != null && content.trim().isEmpty()) {
            content = null;
        }

        JSONArray toolCallsJson = message.optJSONArray("tool_calls");
        if (toolCallsJson != null && toolCallsJson.length() > 0) {
            List<NativeToolCall> calls = new ArrayList<>();
            for (int i = 0; i < toolCallsJson.length(); i++) {
                JSONObject tc = toolCallsJson.getJSONObject(i);
                JSONObject fn = tc.getJSONObject("function");
                String name = fn.optString("name", "").trim();
                if (name.isEmpty()) continue;
                JSONObject args = parseArguments(fn.optString("arguments", "{}"));
                calls.add(new NativeToolCall(tc.optString("id", ""), name, args));
            }
            if (!calls.isEmpty()) {
                return LlmReply.withNativeToolCalls(content, calls);
            }
        }

        String text = content != null ? content.trim() : "";
        return LlmReply.text(PegasePrompt.sanitizeForSpeech(text));
    }

    private static JSONObject parseArguments(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new JSONObject();
        try {
            return new JSONObject(raw.trim());
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
