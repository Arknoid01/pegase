package com.pegasuscorp.orbe.chat;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sonde minimale Groq / Cerebras / OpenRouter — valide les clés sans conversation.
 */
public final class ProviderKeyProbe {

    public static final class Result {
        public final String providerId;
        public final String displayName;
        public final boolean ok;
        public final int httpCode;
        public final long latencyMs;
        public final String detail;

        public Result(String providerId, String displayName, boolean ok, int httpCode,
                long latencyMs, String detail) {
            this.providerId = providerId;
            this.displayName = displayName;
            this.ok = ok;
            this.httpCode = httpCode;
            this.latencyMs = latencyMs;
            this.detail = detail != null ? detail : "";
        }

        public String line() {
            String status = ok ? "OK" : "KO";
            return displayName + " : " + status + " — " + detail;
        }
    }

    private ProviderKeyProbe() {}

    /** Providers de la chaîne (même sans clé) — pour l’UI Réglages. */
    public static List<Result> probeChain(Context context) {
        List<Result> out = new ArrayList<>();
        if (context == null) return out;
        for (LlmProvider p : ProviderChain.buildAll(context, false)) {
            out.add(probe(p));
        }
        return out;
    }

    public static Result probe(LlmProvider provider) {
        if (provider == null) {
            return new Result("?", "?", false, 0, 0, "provider null");
        }
        if (TextUtils.isEmpty(provider.apiKey)) {
            return new Result(provider.id, provider.displayName, false, 0, 0,
                    "clé absente");
        }
        String model = provider.modelId;
        if (TextUtils.isEmpty(model)) {
            return new Result(provider.id, provider.displayName, false, 0, 0,
                    "modèle manquant");
        }
        long t0 = System.currentTimeMillis();
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject()
                    .put("role", "user")
                    .put("content", "ping"));
            body.put("messages", messages);
            body.put("max_tokens", 4);
            body.put("temperature", 0);

            conn = (HttpURLConnection) new URL(provider.chatCompletionsUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + provider.apiKey.trim());
            if (provider.extraHeaders != null) {
                for (Map.Entry<String, String> h : provider.extraHeaders.entrySet()) {
                    conn.setRequestProperty(h.getKey(), h.getValue());
                }
            }
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(20_000);
            conn.setDoOutput(true);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            int code = conn.getResponseCode();
            long latency = System.currentTimeMillis() - t0;
            String errBody = readStream(code < 400 ? null : conn.getErrorStream());
            if (code >= 200 && code < 300) {
                return new Result(provider.id, provider.displayName, true, code, latency,
                        "HTTP " + code + " · " + latency + " ms · " + model);
            }
            String kind = classifyHttp(code, errBody);
            return new Result(provider.id, provider.displayName, false, code, latency,
                    "HTTP " + code + " — " + kind + " · " + model);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - t0;
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new Result(provider.id, provider.displayName, false, 0, latency,
                    "réseau : " + msg);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static String formatReport(List<Result> results) {
        if (results == null || results.isEmpty()) {
            return "Aucun provider à tester.";
        }
        StringBuilder sb = new StringBuilder();
        int ok = 0;
        for (Result r : results) {
            if (r.ok) ok++;
            if (sb.length() > 0) sb.append('\n');
            sb.append(r.line());
        }
        sb.append("\n\n").append(ok).append('/').append(results.size()).append(" OK");
        return sb.toString();
    }

    private static String classifyHttp(int code, String body) {
        String lower = body != null ? body.toLowerCase() : "";
        if (code == 401 || code == 403
                || lower.contains("invalid_api_key")
                || lower.contains("wrong api key")
                || lower.contains("incorrect api key")
                || lower.contains("user not found")
                || lower.contains("unauthorized")) {
            return "clé invalide";
        }
        if (code == 429 || lower.contains("rate_limit")) {
            return "quota / rate limit";
        }
        if (code >= 500) return "service indisponible";
        if (!lower.isEmpty() && lower.length() < 80) return lower.replace('\n', ' ');
        return "erreur";
    }

    private static String readStream(InputStream stream) {
        if (stream == null) return "";
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
