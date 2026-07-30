package com.pegasuscorp.orbe.chat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.pegasuscorp.orbe.contextstore.AttachedContextInjector;
import com.pegasuscorp.orbe.llm.PegasePrompt;
import com.pegasuscorp.orbe.memory.ConversationHistorySelector;
import com.pegasuscorp.orbe.memory.MemoryPromptBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cerveau conversationnel via l'API Gemini (Google AI Studio).
 * Clé API : Tiroir → ⚙️ → Clés API, ou https://aistudio.google.com
 */
public class GeminiChatBackend implements ChatBackend {

    private final Context appContext;
    private final String apiKey;
    private final String modelOverride;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public GeminiChatBackend(Context context, String apiKey) {
        this(context, apiKey, null);
    }

    public GeminiChatBackend(Context context, String apiKey, String modelOverride) {
        this.appContext = context.getApplicationContext();
        this.apiKey = apiKey;
        this.modelOverride = modelOverride;
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public String traceBackendLabel() {
        String model = modelOverride != null ? modelOverride : CloudModelStore.getGeminiModelId(appContext);
        return "Gemini/" + model;
    }

    @Override
    public void send(List<Turn> history, String userMessage, OnReply callback) {
        if (TextUtils.isEmpty(apiKey)) {
            callback.onError("Clé Gemini manquante. Va dans Réglages → Clés API.");
            return;
        }
        boolean stream = callback instanceof StreamOnReply;
        io.execute(() -> {
            try {
                String body = buildBody(history, userMessage);
                if (stream) {
                    streamPost(body, (StreamOnReply) callback);
                } else {
                    String reply = post(body);
                    main.post(() -> callback.onReply(reply));
                }
            } catch (Exception e) {
                android.util.Log.e("GeminiChat", "Erreur", e);
                main.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    private String buildBody(List<Turn> history, String userMessage) throws Exception {
        JSONArray contents = new JSONArray();
        List<Turn> promptHistory = ConversationHistorySelector.selectForPrompt(
                appContext, history, userMessage);
        for (Turn t : promptHistory) {
            if (t.system) {
                // Gemini : pas de rôle system dans contents — préfixe user
                contents.put(turn("user", "[Contexte] " + t.text));
            } else {
                contents.put(turn(t.fromUser ? "user" : "model", t.text));
            }
        }
        contents.put(turn("user",
                AttachedContextInjector.wrapUserMessage(appContext, userMessage)));

        JSONObject root = new JSONObject();
        root.put("contents", contents);

        JSONObject sysInstr = new JSONObject().put("parts",
                new JSONArray().put(new JSONObject().put("text",
                        MemoryPromptBuilder.buildFullSystem(appContext, userMessage))));
        root.put("systemInstruction", sysInstr);

        return root.toString();
    }

    private JSONObject turn(String role, String text) throws Exception {
        return new JSONObject()
                .put("role", role)
                .put("parts", new JSONArray().put(new JSONObject().put("text", text)));
    }

    private String endpoint() {
        String model = modelOverride != null ? modelOverride : CloudModelStore.getGeminiModelId(appContext);
        return "https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent?key=";
    }

    private String streamEndpoint() {
        String model = modelOverride != null ? modelOverride : CloudModelStore.getGeminiModelId(appContext);
        return "https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":streamGenerateContent?alt=sse&key=";
    }

    private void streamPost(String body, StreamOnReply callback) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(streamEndpoint() + apiKey).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120_000);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        if (code >= 400) {
            BufferedReader err = new BufferedReader(new InputStreamReader(
                    conn.getErrorStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = err.readLine()) != null) sb.append(line);
            err.close();
            throw new RuntimeException("HTTP " + code + " : " + sb);
        }
        StringBuilder accumulated = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                String delta = extractGeminiStreamDelta(data);
                if (delta == null || delta.isEmpty()) continue;
                accumulated.append(delta);
                String snap = accumulated.toString();
                main.post(() -> callback.onPartial(snap));
            }
        }
        String full = PegasePrompt.sanitizeForSpeech(accumulated.toString().trim());
        main.post(() -> callback.onReply(full));
    }

    private static String extractGeminiStreamDelta(String jsonLine) {
        try {
            JSONObject obj = new JSONObject(jsonLine);
            JSONArray candidates = obj.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) return null;
            JSONArray parts = candidates.getJSONObject(0)
                    .optJSONObject("content")
                    .optJSONArray("parts");
            if (parts == null || parts.length() == 0) return null;
            return parts.getJSONObject(0).optString("text", null);
        } catch (Exception e) {
            return null;
        }
    }

    private String post(String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint() + apiKey).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        BufferedReader br = new BufferedReader(new InputStreamReader(
                code < 400 ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();

        if (code >= 400) {
            throw new RuntimeException("HTTP " + code + " : " + sb);
        }

        JSONObject json = new JSONObject(sb.toString());
        if (json.has("text")) {
            return PegasePrompt.sanitizeForSpeech(json.getString("text").trim());
        }
        String text = json.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim();
        return PegasePrompt.sanitizeForSpeech(text);
    }
}
